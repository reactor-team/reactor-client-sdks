import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createTrackStatsExtractor,
  createConnectionStatsExtractor,
  pollPeerStats,
  toClientConnectionStat,
} from './stats';
import type { TrackMappingEntry } from './reactor-wasm.types';

function makeReport(entries: Array<[string, unknown]>) {
  const map = new Map(entries);

  return {
    forEach: (cb: (value: unknown, key: string) => void) => map.forEach(cb),
    get: (key: string) => map.get(key),
  } as unknown as RTCStatsReport;
}

describe('createConnectionStatsExtractor()', () => {
  it('extracts RTT, candidate type, bitrate, FPS, jitter, and packet loss', () => {
    const report = makeReport([
      [
        'cp1',
        {
          type: 'candidate-pair',
          state: 'succeeded',
          nominated: true,
          currentRoundTripTime: 0.025,
          availableOutgoingBitrate: 1_000_000,
          localCandidateId: 'lc1',
        },
      ],
      ['lc1', { type: 'local-candidate', candidateType: 'host' }],
      [
        'ir1',
        {
          type: 'inbound-rtp',
          kind: 'video',
          framesPerSecond: 30,
          jitter: 0.01,
          packetsReceived: 990,
          packetsLost: 10,
        },
      ],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBe(25);
    expect(stats.candidateType).toBe('host');
    expect(stats.availableOutgoingBitrate).toBe(1_000_000);
    expect(stats.availableIncomingBitrate).toBeUndefined();
    expect(stats.framesPerSecond).toBe(30);
    expect(stats.jitter).toBe(0.01);
    expect(stats.packetLossRatio).toBeCloseTo(0.01);
    expect(stats.timestamp).toBeGreaterThan(0);
  });

  it('returns undefined fields for an empty report', () => {
    const report = makeReport([]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBeUndefined();
    expect(stats.candidateType).toBeUndefined();
    expect(stats.availableIncomingBitrate).toBeUndefined();
    expect(stats.framesPerSecond).toBeUndefined();
    expect(stats.timestamp).toBeGreaterThan(0);
  });

  it('leaves rtt undefined when currentRoundTripTime is missing', () => {
    const report = makeReport([
      [
        'cp1',
        {
          type: 'candidate-pair',
          state: 'succeeded',
          nominated: true,
          availableOutgoingBitrate: 500_000,
          localCandidateId: 'lc1',
        },
      ],
      ['lc1', { type: 'local-candidate', candidateType: 'srflx' }],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBeUndefined();
    expect(stats.availableOutgoingBitrate).toBe(500_000);
    expect(stats.candidateType).toBe('srflx');
  });

  it('leaves candidateType undefined when the local candidate is missing', () => {
    const report = makeReport([
      [
        'cp1',
        {
          type: 'candidate-pair',
          state: 'succeeded',
          nominated: true,
          currentRoundTripTime: 0.05,
          localCandidateId: 'lc-missing',
        },
      ],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBe(50);
    expect(stats.candidateType).toBeUndefined();
  });

  it('ignores a candidate-pair that is not nominated, or not succeeded', () => {
    const report = makeReport([
      ['cp1', { type: 'candidate-pair', state: 'succeeded', nominated: false, currentRoundTripTime: 0.01 }],
      ['cp2', { type: 'candidate-pair', state: 'in-progress', nominated: true, currentRoundTripTime: 0.02 }],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBeUndefined();
  });

  it('only reads the first nominated candidate-pair when more than one is present', () => {
    const report = makeReport([
      ['cp1', { id: 'cp1', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.01 }],
      ['cp2', { id: 'cp2', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.09 }],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBe(10);
  });

  it("reads the transport's selected candidate-pair when several are nominated", () => {
    const report = makeReport([
      ['cp1', { id: 'cp1', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.01 }],
      [
        'cp2',
        {
          id: 'cp2',
          type: 'candidate-pair',
          state: 'succeeded',
          nominated: true,
          currentRoundTripTime: 0.002,
          availableOutgoingBitrate: 5_465_846,
        },
      ],
      ['t1', { id: 't1', type: 'transport', selectedCandidatePairId: 'cp2' }],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBe(2);
    expect(stats.availableOutgoingBitrate).toBe(5_465_846);
  });

  it('passes over a pair the transport names when it has not succeeded', () => {
    const report = makeReport([
      ['cp1', { id: 'cp1', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.01 }],
      ['cp2', { id: 'cp2', type: 'candidate-pair', state: 'in-progress', currentRoundTripTime: 0.09 }],
      ['t1', { id: 't1', type: 'transport', selectedCandidatePairId: 'cp2' }],
    ]);

    expect(createConnectionStatsExtractor()(report).rtt).toBe(10);
  });

  it('reads the candidate-pair flagged selected when there is no transport stat', () => {
    const report = makeReport([
      ['cp1', { id: 'cp1', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.01 }],
      [
        'cp2',
        {
          id: 'cp2',
          type: 'candidate-pair',
          state: 'succeeded',
          nominated: true,
          selected: true,
          currentRoundTripTime: 0.003,
          availableOutgoingBitrate: 2_000_000,
        },
      ],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBe(3);
    expect(stats.availableOutgoingBitrate).toBe(2_000_000);
  });

  it('computes incomingBitrate and outgoingBitrate from candidate-pair counters between samples', () => {
    const baseTimestamp = 1_777_674_503_920;
    const makeCandidatePairReport = (timestamp: number, bytesReceived: number, bytesSent: number) =>
      makeReport([
        [
          'cp1',
          {
            type: 'candidate-pair',
            state: 'succeeded',
            nominated: true,
            timestamp,
            bytesReceived,
            bytesSent,
            localCandidateId: 'lc1',
          },
        ],
        ['lc1', { type: 'local-candidate', candidateType: 'host' }],
      ]);

    const extract = createConnectionStatsExtractor();

    const first = extract(makeCandidatePairReport(baseTimestamp, 1_000_000, 1_025_000));

    expect(first.incomingBitrate).toBeUndefined();
    expect(first.outgoingBitrate).toBeUndefined();

    // 1600 ms later: +500,000 bytes received, +700,000 bytes sent.
    const timeDiffMs = 1_600;
    const second = extract(makeCandidatePairReport(baseTimestamp + timeDiffMs, 1_500_000, 1_725_000));

    expect(second.incomingBitrate).toBe(2_500_000);
    expect(second.outgoingBitrate).toBe(3_500_000);
  });

  it('resets the bitrate baseline when the nominated candidate-pair changes (an ICE restart or failover)', () => {
    const baseTimestamp = 1_777_674_503_920;
    const makeCandidatePairReport = (id: string, timestamp: number, bytesReceived: number, bytesSent: number) =>
      makeReport([
        [
          id,
          {
            id,
            type: 'candidate-pair',
            state: 'succeeded',
            nominated: true,
            timestamp,
            bytesReceived,
            bytesSent,
            localCandidateId: 'lc1',
          },
        ],
        ['lc1', { type: 'local-candidate', candidateType: 'host' }],
      ]);

    const extract = createConnectionStatsExtractor();

    extract(makeCandidatePairReport('cp1', baseTimestamp, 1_000_000, 1_025_000));
    const second = extract(makeCandidatePairReport('cp1', baseTimestamp + 1_600, 1_500_000, 1_725_000));

    expect(second.incomingBitrate).toBe(2_500_000);

    // ICE nominates a different pair — its byte counters start from their
    // own, unrelated baseline, so this sample must not diff against cp1's.
    const third = extract(makeCandidatePairReport('cp2', baseTimestamp + 3_200, 10, 20));

    expect(third.incomingBitrate).toBeUndefined();
    expect(third.outgoingBitrate).toBeUndefined();

    // cp2's own next sample establishes a baseline and diffs normally.
    const fourth = extract(makeCandidatePairReport('cp2', baseTimestamp + 4_800, 210, 420));

    expect(fourth.incomingBitrate).toBe(1_000);
    expect(fourth.outgoingBitrate).toBe(2_000);
  });

  it('ignores a second video inbound-rtp stat when one was already read', () => {
    const report = makeReport([
      ['ir1', { id: 'ir1', type: 'inbound-rtp', kind: 'video', framesPerSecond: 30 }],
      ['ir2', { id: 'ir2', type: 'inbound-rtp', kind: 'video', framesPerSecond: 15 }],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.framesPerSecond).toBe(30);
  });

  it('ignores a non-video inbound-rtp stat', () => {
    const report = makeReport([['ir1', { type: 'inbound-rtp', kind: 'audio', framesPerSecond: 30 }]]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.framesPerSecond).toBeUndefined();
  });

  it('leaves packetLossRatio undefined when no packets have been received or lost yet', () => {
    const report = makeReport([
      ['ir1', { type: 'inbound-rtp', kind: 'video', packetsReceived: 0, packetsLost: 0 }],
    ]);

    const extract = createConnectionStatsExtractor();
    const stats = extract(report);

    expect(stats.packetLossRatio).toBeUndefined();
  });

});

describe('createTrackStatsExtractor()', () => {
  const tracks: TrackMappingEntry[] = [
    { name: 'webcam', kind: 'video', direction: 'sendonly', mid: '0' },
    { name: 'mic', kind: 'audio', direction: 'sendonly', mid: '1' },
    { name: 'main_video', kind: 'video', direction: 'recvonly', mid: '2' },
    { name: 'main_audio', kind: 'audio', direction: 'recvonly', mid: '3' },
  ];

  function fullReport(timestamp: number, bytes: number) {
    return makeReport([
      ['cp1', { id: 'cp1', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.05 }],
      ['cv', { id: 'cv', type: 'codec', mimeType: 'video/VP8' }],
      ['ca', { id: 'ca', type: 'codec', mimeType: 'audio/opus' }],
      [
        'ov',
        {
          id: 'ov',
          type: 'outbound-rtp',
          kind: 'video',
          mid: '0',
          codecId: 'cv',
          timestamp,
          bytesSent: bytes,
          packetsSent: 900,
          retransmittedPacketsSent: 5,
          nackCount: 4,
          firCount: 1,
          pliCount: 2,
          framesPerSecond: 30,
          framesEncoded: 600,
          frameWidth: 640,
          frameHeight: 480,
        },
      ],
      [
        'rv',
        { id: 'rv', type: 'remote-inbound-rtp', kind: 'video', localId: 'ov', packetsLost: 3, jitter: 0.004, roundTripTime: 0.021 },
      ],
      ['oa', { id: 'oa', type: 'outbound-rtp', kind: 'audio', mid: '1', codecId: 'ca', timestamp, bytesSent: bytes / 10, packetsSent: 500 }],
      [
        'iv',
        {
          id: 'iv',
          type: 'inbound-rtp',
          kind: 'video',
          mid: '2',
          codecId: 'cv',
          timestamp,
          bytesReceived: bytes,
          packetsReceived: 996,
          packetsLost: 4,
          jitter: 0.012,
          nackCount: 3,
          firCount: 0,
          pliCount: 1,
          framesPerSecond: 29.5,
          framesDecoded: 900,
          framesDropped: 2,
          frameWidth: 1280,
          frameHeight: 720,
        },
      ],
      [
        'ia',
        {
          id: 'ia',
          type: 'inbound-rtp',
          kind: 'audio',
          mid: '3',
          codecId: 'ca',
          timestamp,
          bytesReceived: bytes / 10,
          packetsReceived: 480,
          packetsLost: 1,
          jitter: 0.002,
          concealedSamples: 960,
          totalSamplesReceived: 480_000,
        },
      ],
    ]);
  }

  it('reports every negotiated track, audio and video, sent and received', () => {
    const extract = createTrackStatsExtractor();
    const stats = extract(fullReport(1_000, 100_000), tracks, []);

    expect(stats.map((s) => [s.trackName, s.kind, s.direction, s.codec])).toEqual([
      ['webcam', 'video', 'sendonly', 'vp8'],
      ['mic', 'audio', 'sendonly', 'opus'],
      ['main_video', 'video', 'recvonly', 'vp8'],
      ['main_audio', 'audio', 'recvonly', 'opus'],
    ]);
  });

  it('carries each direction and kind its own metric set, in wire units', () => {
    const extract = createTrackStatsExtractor();
    const [webcam, mic, mainVideo, mainAudio] = extract(fullReport(1_000, 100_000), tracks, []);

    expect(webcam?.metrics).toEqual({
      packets_sent: 900,
      retransmitted_packets_sent: 5,
      nack_count: 4,
      packets_lost: 3,
      jitter_ms: 4,
      round_trip_time_ms: 21,
      frames_per_second: 30,
      frames_encoded: 600,
      frame_width: 640,
      frame_height: 480,
      keyframe_requests: 3,
    });
    expect(mic?.metrics).toEqual({ packets_sent: 500 });
    expect(mainVideo?.metrics).toEqual({
      packets_received: 996,
      packets_lost: 4,
      jitter_ms: 12,
      nack_count: 3,
      frames_per_second: 29.5,
      frames_decoded: 900,
      frames_dropped: 2,
      frame_width: 1280,
      frame_height: 720,
      keyframe_requests: 1,
    });
    expect(mainAudio?.metrics).toEqual({
      packets_received: 480,
      packets_lost: 1,
      jitter_ms: 2,
      concealed_samples: 960,
      total_samples_received: 480_000,
    });
  });

  it('converts durations to milliseconds without binary float noise', () => {
    const report = makeReport([
      ['ia', { id: 'ia', type: 'inbound-rtp', kind: 'audio', mid: '3', codecId: 'ca', timestamp: 1, jitter: 0.000811 }],
      ['ca', { id: 'ca', type: 'codec', mimeType: 'audio/opus' }],
    ]);

    const [mainAudio] = createTrackStatsExtractor()(report, tracks, []);

    // A plain `0.000811 * 1000` is 0.8109999999999999.
    expect(mainAudio?.metrics.jitter_ms).toBe(0.811);
  });

  it('reports a per-stream bitrate from the second batch on, averaged since the previous one', () => {
    const extract = createTrackStatsExtractor();

    extract(fullReport(1_000, 100_000), tracks, []);
    const stats = extract(fullReport(11_000, 1_350_000), tracks, []);

    // (1,350,000 - 100,000) bytes * 8 over 10 s.
    expect(stats.find((s) => s.trackName === 'main_video')?.metrics.bitrate_bps).toBe(1_000_000);
    expect(stats.find((s) => s.trackName === 'webcam')?.metrics.bitrate_bps).toBe(1_000_000);
    expect(stats.find((s) => s.trackName === 'main_audio')?.metrics.bitrate_bps).toBe(100_000);
  });

  it('carries the paused flag per track', () => {
    const extract = createTrackStatsExtractor();
    const stats = extract(fullReport(1_000, 100_000), tracks, ['webcam']);

    expect(stats.find((s) => s.trackName === 'webcam')?.paused).toBe(true);
    expect(stats.find((s) => s.trackName === 'main_video')?.paused).toBe(false);
  });

  it('skips a stream whose mid has not negotiated onto a named track of its direction', () => {
    const extract = createTrackStatsExtractor();
    const stats = extract(fullReport(1_000, 100_000), [{ ...tracks[2]!, direction: 'sendonly' }], []);

    expect(stats).toEqual([]);
  });

  it('skips a stream with no codec yet, or one the wire cannot carry, keeping the rest', () => {
    const extract = createTrackStatsExtractor();
    const report = makeReport([
      ['cx', { id: 'cx', type: 'codec', mimeType: 'video/X-UNKNOWN' }],
      ['ca', { id: 'ca', type: 'codec', mimeType: 'audio/opus' }],
      ['iv', { id: 'iv', type: 'inbound-rtp', kind: 'video', mid: '2', codecId: 'cx', timestamp: 1_000 }],
      ['ov', { id: 'ov', type: 'outbound-rtp', kind: 'video', mid: '0', timestamp: 1_000 }],
      ['ia', { id: 'ia', type: 'inbound-rtp', kind: 'audio', mid: '3', codecId: 'ca', timestamp: 1_000 }],
    ]);

    expect(extract(report, tracks, []).map((s) => s.trackName)).toEqual(['main_audio']);
  });

  it('reports one reading per track when several streams share its mid', () => {
    const extract = createTrackStatsExtractor();
    const report = makeReport([
      ['cv', { id: 'cv', type: 'codec', mimeType: 'video/VP8' }],
      ['ov1', { id: 'ov1', type: 'outbound-rtp', kind: 'video', mid: '0', codecId: 'cv', timestamp: 1_000, frameWidth: 640 }],
      ['ov2', { id: 'ov2', type: 'outbound-rtp', kind: 'video', mid: '0', codecId: 'cv', timestamp: 1_000, frameWidth: 320 }],
    ]);
    const stats = extract(report, tracks, []);

    expect(stats).toHaveLength(1);
    expect(stats[0]?.metrics.frame_width).toBe(640);
  });
});

describe('toClientConnectionStat()', () => {
  const activePair = {
    id: 'cp1',
    type: 'candidate-pair',
    state: 'succeeded',
    nominated: true,
    currentRoundTripTime: 0.025,
    availableOutgoingBitrate: 2_000_000,
  };

  it('reads the active pair on every report, and the time to connect only when given', () => {
    const report = makeReport([['cp1', activePair]]);

    expect(toClientConnectionStat(report, 850).metrics).toEqual({
      available_outgoing_bitrate_bps: 2_000_000,
      connection_rtt_ms: 25,
      time_to_connect_ms: 850,
    });
    expect(toClientConnectionStat(report).metrics).toEqual({
      available_outgoing_bitrate_bps: 2_000_000,
      connection_rtt_ms: 25,
    });
  });

  it('carries the receive-side estimate when the browser has one', () => {
    const report = makeReport([['cp1', { ...activePair, availableIncomingBitrate: 5_000_000 }]]);

    expect(toClientConnectionStat(report).metrics).toMatchObject({ available_incoming_bitrate_bps: 5_000_000 });
  });

  it("reads the transport's selected pair, not an idle nominated one", () => {
    const report = makeReport([
      ['cp0', { id: 'cp0', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.001 }],
      ['cp1', activePair],
      ['t1', { id: 't1', type: 'transport', selectedCandidatePairId: 'cp1' }],
    ]);

    expect(toClientConnectionStat(report).metrics).toEqual({
      available_outgoing_bitrate_bps: 2_000_000,
      connection_rtt_ms: 25,
    });
  });

  it('is the same on every call: it keeps no state between reports', () => {
    const report = makeReport([['cp1', activePair]]);

    expect(toClientConnectionStat(report).metrics).toEqual(toClientConnectionStat(report).metrics);
  });

  it('leaves out what the browser has not produced yet', () => {
    expect(toClientConnectionStat(makeReport([])).metrics).toEqual({});
  });
});

describe('pollPeerStats()', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('hands every interval\'s report to the handler, skipping ticks without a peer connection', async () => {
    const report = makeReport([]);
    const peer: { connection?: RTCPeerConnection } = {};
    const onReport = vi.fn();

    const stop = pollPeerStats({ getPeerConnection: () => peer.connection }, 1_000, onReport);

    await vi.advanceTimersByTimeAsync(1_000);
    expect(onReport).not.toHaveBeenCalled();

    peer.connection = { getStats: () => Promise.resolve(report) } as unknown as RTCPeerConnection;
    await vi.advanceTimersByTimeAsync(2_000);
    expect(onReport).toHaveBeenCalledTimes(2);
    expect(onReport).toHaveBeenCalledWith(report);

    stop();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(onReport).toHaveBeenCalledTimes(2);
  });

  it('drops a read that resolves after it was stopped', async () => {
    let resolveRead: (report: RTCStatsReport) => void = () => {};
    const peerConnection = {
      getStats: () =>
        new Promise<RTCStatsReport>((resolve) => {
          resolveRead = resolve;
        }),
    } as unknown as RTCPeerConnection;
    const onReport = vi.fn();

    const stop = pollPeerStats({ getPeerConnection: () => peerConnection }, 1_000, onReport);

    await vi.advanceTimersByTimeAsync(1_000);
    stop();
    resolveRead(makeReport([]));
    await vi.advanceTimersByTimeAsync(0);

    expect(onReport).not.toHaveBeenCalled();
  });

  it('logs an error thrown by the handler and keeps polling', async () => {
    const peerConnection = {
      getStats: () => Promise.resolve(makeReport([])),
    } as unknown as RTCPeerConnection;
    const failure = new Error('handler bug');
    const onReport = vi.fn(() => {
      throw failure;
    });
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    const stop = pollPeerStats({ getPeerConnection: () => peerConnection }, 1_000, onReport);

    await vi.advanceTimersByTimeAsync(2_000);
    stop();

    expect(onReport).toHaveBeenCalledTimes(2);
    expect(consoleError).toHaveBeenCalledWith('[Reactor] stats report handler threw:', failure);
    consoleError.mockRestore();
  });

  it('skips a tick whose read rejects', async () => {
    const peerConnection = {
      getStats: () => Promise.reject(new Error('closing')),
    } as unknown as RTCPeerConnection;
    const onReport = vi.fn();

    const stop = pollPeerStats({ getPeerConnection: () => peerConnection }, 1_000, onReport);

    await vi.advanceTimersByTimeAsync(1_000);
    stop();

    expect(onReport).not.toHaveBeenCalled();
  });
});
