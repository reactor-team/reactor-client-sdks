import { describe, expect, it } from 'vitest';
import { createRTCStatsExtractor } from './stats';

function makeReport(entries: Array<[string, unknown]>) {
  const map = new Map(entries);

  return {
    forEach: (cb: (value: unknown, key: string) => void) => map.forEach(cb),
    get: (key: string) => map.get(key),
  } as unknown as RTCStatsReport;
}

describe('createRTCStatsExtractor()', () => {
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

    const extract = createRTCStatsExtractor();
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

    const extract = createRTCStatsExtractor();
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

    const extract = createRTCStatsExtractor();
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

    const extract = createRTCStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBe(50);
    expect(stats.candidateType).toBeUndefined();
  });

  it('ignores a candidate-pair that is not nominated, or not succeeded', () => {
    const report = makeReport([
      ['cp1', { type: 'candidate-pair', state: 'succeeded', nominated: false, currentRoundTripTime: 0.01 }],
      ['cp2', { type: 'candidate-pair', state: 'in-progress', nominated: true, currentRoundTripTime: 0.02 }],
    ]);

    const extract = createRTCStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBeUndefined();
  });

  it('only reads the first nominated candidate-pair when more than one is present', () => {
    const report = makeReport([
      ['cp1', { id: 'cp1', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.01 }],
      ['cp2', { id: 'cp2', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 0.09 }],
    ]);

    const extract = createRTCStatsExtractor();
    const stats = extract(report);

    expect(stats.rtt).toBe(10);
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

    const extract = createRTCStatsExtractor();

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

    const extract = createRTCStatsExtractor();

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

    const extract = createRTCStatsExtractor();
    const stats = extract(report);

    expect(stats.framesPerSecond).toBe(30);
  });

  it('ignores a non-video inbound-rtp stat', () => {
    const report = makeReport([['ir1', { type: 'inbound-rtp', kind: 'audio', framesPerSecond: 30 }]]);

    const extract = createRTCStatsExtractor();
    const stats = extract(report);

    expect(stats.framesPerSecond).toBeUndefined();
  });

  it('leaves packetLossRatio undefined when no packets have been received or lost yet', () => {
    const report = makeReport([
      ['ir1', { type: 'inbound-rtp', kind: 'video', packetsReceived: 0, packetsLost: 0 }],
    ]);

    const extract = createRTCStatsExtractor();
    const stats = extract(report);

    expect(stats.packetLossRatio).toBeUndefined();
  });
});
