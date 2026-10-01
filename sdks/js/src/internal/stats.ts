import type { ConnectionStats } from '../types';
import type {
  ClientConnectionStat,
  ClientTrackStat,
  ReactorClient,
  TrackDirection,
  TrackKind,
  TrackMappingEntry,
} from './reactor-wasm.types';

// Two intervals, so the local `statsUpdate` cadence and the runtime report
// cadence can each change without affecting the other.

/** How often `Reactor` reads `getStats()` while ready to update the stats it
 *  exposes inside the SDK: `getStats()` and the `statsUpdate` event. Nothing
 *  read on this timer leaves the browser. */
export const LOCAL_STATS_INTERVAL_MS = 2_000;

/** How often `Reactor` sends a client-stats batch to the runtime while ready.
 *  Runs on its own timer and its own `getStats()` read, apart from
 *  `LOCAL_STATS_INTERVAL_MS`, so each batch's bitrates cover the whole
 *  interval since the previous batch. */
export const RUNTIME_REPORT_INTERVAL_MS = 5_000;

/** Reads `getStats()` off *client*'s peer connection every *intervalMs* and
 *  hands each report to *onReport*. A tick without a peer connection, or
 *  whose read rejects (the connection may be closing), is skipped. An error
 *  thrown by *onReport* itself is a bug, not a closing connection, so it is
 *  logged rather than swallowed, and polling goes on.
 *
 *  Returns the function that stops it. Clearing the interval can't cancel a
 *  `getStats()` already in flight, so a read that resolves after the stop is
 *  dropped instead of reaching *onReport*: a recoverable disconnect can
 *  restart polling on the same client, and the old read must not land in the
 *  new one. */
export function pollPeerStats(
  client: Pick<ReactorClient, 'getPeerConnection'>,
  intervalMs: number,
  onReport: (report: RTCStatsReport) => void,
): () => void {
  let stopped = false;
  const handle = setInterval(() => {
    const peerConnection = client.getPeerConnection();

    if (!peerConnection) {
      return;
    }
    peerConnection.getStats().then(
      (report) => {
        if (stopped) {
          return;
        }
        try {
          onReport(report);
        } catch (error) {
          console.error('[Reactor] stats report handler threw:', error);
        }
      },
      () => {
        // Connection may be closing.
      },
    );
  }, intervalMs);

  return () => {
    stopped = true;
    clearInterval(handle);
  };
}

/** A WebRTC duration in seconds as milliseconds for the runtime report,
 *  rounded to the microsecond. `getStats()` reports seconds as binary
 *  floats, so a plain `* 1000` sends values like `0.8109999999999999`. */
function secondsToMs(seconds: number): number {
  return Math.round(seconds * 1_000_000) / 1_000;
}

/** The codecs the wire's `VideoCodec`/`AudioCodec` enums can carry, per
 *  track kind. `sendClientStats` rejects the whole batch over one codec
 *  outside this set, so a track negotiated onto anything else is left out
 *  of the batch rather than taking every other track's reading down with it.
 *
 *  Mirrors `client_track_codec` in `crates/reactor-core/src/stats.rs`, whose
 *  test fails when the wire gains a codec it doesn't map: add it there and
 *  here together. */
const WIRE_CODECS: Record<TrackKind, ReadonlySet<string>> = {
  video: new Set(['vp8', 'vp9', 'av1', 'h264', 'h265']),
  audio: new Set(['opus']),
};

/** The previous reading of one RTP stream's byte counter, for turning it
 *  into a bitrate. */
interface ByteSample {
  bytes: number;
  timestamp: number;
}

/** Builds one batch of the wire's `ClientTrackStat`s from a stats report, a
 *  reading per negotiated track — audio and video, sent and received. */
export type TrackStatsExtractor = (
  report: RTCStatsReport,
  tracks: readonly TrackMappingEntry[],
  pausedTracks: readonly string[],
) => ClientTrackStat[];

/**
 * The per-track part of the runtime report: one reading per track, every
 * `RUNTIME_REPORT_INTERVAL_MS`. Not used for `getStats()` / `statsUpdate`.
 *
 * A closure over each RTP stream's previous byte counter, needed to turn the
 * cumulative `bytesReceived`/`bytesSent` into a bitrate averaged over the
 * interval since the previous batch. A stream's first batch carries no
 * `bitrate_bps` — there is nothing to diff against yet.
 *
 * Each `inbound-rtp` (a `recvonly` track) and `outbound-rtp` (a `sendonly`
 * track) resolves to its track through its `mid`; a stream on a `mid` that
 * hasn't negotiated onto a named track, or whose codec isn't known yet, is
 * skipped. A sent stream's loss, jitter and round-trip time are what the
 * remote end reported back for it (its `remote-inbound-rtp`), the only
 * vantage point onto them. A received stream carries no round-trip time: a
 * receiver has no per-stream one, so the connection's own ICE round-trip time
 * goes on the connection stat instead (`connection_rtt_ms`). With several
 * streams on one `mid` (simulcast layers), the first one reported stands for
 * the track.
 *
 * Reports raw cumulative counters (`packets_lost`/`packets_received`, not a
 * pre-computed loss ratio) so the runtime's downstream aggregation across
 * sessions stays a sum of sums — a pre-averaged ratio can't be re-aggregated
 * correctly. A reading the browser hasn't produced is left out of `metrics`
 * rather than reported as `0`, so it can't pass for a real zero.
 */
export function createTrackStatsExtractor(): TrackStatsExtractor {
  const lastBytes = new Map<string, ByteSample>();

  return (report, tracks, pausedTracks) => {
    const reportWithLookup = report as RTCStatsReportWithLookup;
    const stats: ClientTrackStat[] = [];
    // Keyed by direction too: a sendrecv transceiver shares one mid between
    // its sent and received stream.
    const seenStreams = new Set<string>();
    const remoteInbound = new Map<string, RTCStatsReportEntry>();

    report.forEach((stat: RTCStatsReportEntry) => {
      if (stat.type === 'remote-inbound-rtp' && stat.localId !== undefined) {
        remoteInbound.set(stat.localId, stat);
      }
    });

    report.forEach((stat: RTCStatsReportEntry) => {
      const inbound = stat.type === 'inbound-rtp';

      if ((!inbound && stat.type !== 'outbound-rtp') || stat.mid === undefined) {
        return;
      }
      const direction: TrackDirection = inbound ? 'recvonly' : 'sendonly';
      const streamKey = `${direction}:${stat.mid}`;

      if (seenStreams.has(streamKey)) {
        return;
      }
      const track = tracks.find((entry) => entry.mid === stat.mid && entry.direction === direction);
      const codecStat = stat.codecId !== undefined ? reportWithLookup.get(stat.codecId) : undefined;
      // "video/VP9" -> "vp9".
      const codec = codecStat?.mimeType?.split('/')[1]?.toLowerCase();

      if (track === undefined || codec === undefined || !WIRE_CODECS[track.kind].has(codec)) {
        return;
      }
      seenStreams.add(streamKey);

      const metrics: Record<string, number> = {};
      const put = (key: string, value: number | undefined) => {
        if (value !== undefined) {
          metrics[key] = value;
        }
      };
      const bytes = inbound ? stat.bytesReceived : stat.bytesSent;

      if (bytes !== undefined) {
        const previous = lastBytes.get(stat.id);

        if (previous !== undefined && stat.timestamp > previous.timestamp && bytes >= previous.bytes) {
          put('bitrate_bps', Math.round(((bytes - previous.bytes) * 8 * 1000) / (stat.timestamp - previous.timestamp)));
        }
        lastBytes.set(stat.id, { bytes, timestamp: stat.timestamp });
      }
      const keyframeRequests =
        stat.firCount !== undefined || stat.pliCount !== undefined
          ? (stat.firCount ?? 0) + (stat.pliCount ?? 0)
          : undefined;

      if (inbound) {
        put('packets_received', stat.packetsReceived);
        put('packets_lost', stat.packetsLost);
        put('jitter_ms', stat.jitter !== undefined ? secondsToMs(stat.jitter) : undefined);
        put('nack_count', stat.nackCount);
        if (track.kind === 'video') {
          put('frames_per_second', stat.framesPerSecond);
          put('frames_decoded', stat.framesDecoded);
          put('frames_dropped', stat.framesDropped);
          put('frame_width', stat.frameWidth);
          put('frame_height', stat.frameHeight);
          put('keyframe_requests', keyframeRequests);
        } else {
          put('concealed_samples', stat.concealedSamples);
          put('total_samples_received', stat.totalSamplesReceived);
        }
      } else {
        const remote = remoteInbound.get(stat.id);

        put('packets_sent', stat.packetsSent);
        put('retransmitted_packets_sent', stat.retransmittedPacketsSent);
        put('nack_count', stat.nackCount);
        put('packets_lost', remote?.packetsLost);
        put('jitter_ms', remote?.jitter !== undefined ? secondsToMs(remote.jitter) : undefined);
        put('round_trip_time_ms', remote?.roundTripTime !== undefined ? secondsToMs(remote.roundTripTime) : undefined);
        if (track.kind === 'video') {
          put('frames_per_second', stat.framesPerSecond);
          put('frames_encoded', stat.framesEncoded);
          put('frame_width', stat.frameWidth);
          put('frame_height', stat.frameHeight);
          put('keyframe_requests', keyframeRequests);
        }
      }

      stats.push({
        timestamp: Date.now(),
        trackName: track.name,
        kind: track.kind,
        direction,
        codec,
        paused: pausedTracks.includes(track.name),
        metrics,
      });
    });

    return stats;
  };
}

/**
 * The connection-wide part of the runtime report, read straight off one stats
 * report — the facts that belong to the connection, not to any one track.
 * Every value is a point-in-time reading of the active candidate-pair (see
 * `activeCandidatePair()`), so nothing here depends on a previous report.
 *
 * - `available_outgoing_bitrate_bps`: the congestion controller's current
 *   send-side estimate.
 * - `available_incoming_bitrate_bps`: the receive-side estimate, which a
 *   browser only has when it estimates as the receiver.
 * - `connection_rtt_ms`: the ICE round-trip time, one value for the whole
 *   connection.
 * - `time_to_connect_ms`: *timeToConnectMs*, a one-time fact the caller passes
 *   only on the first report after connecting.
 *
 * Like a track's metrics, a reading the browser hasn't produced is left out
 * rather than reported as `0`, so it can't pass for a collapsed estimate.
 */
export function toClientConnectionStat(report: RTCStatsReport, timeToConnectMs?: number): ClientConnectionStat {
  const pair = activeCandidatePair(report);
  const metrics: Record<string, number> = {};

  if (pair?.availableOutgoingBitrate !== undefined) {
    metrics.available_outgoing_bitrate_bps = pair.availableOutgoingBitrate;
  }
  if (pair?.availableIncomingBitrate !== undefined) {
    metrics.available_incoming_bitrate_bps = pair.availableIncomingBitrate;
  }
  if (pair?.currentRoundTripTime !== undefined) {
    metrics.connection_rtt_ms = secondsToMs(pair.currentRoundTripTime);
  }
  if (timeToConnectMs !== undefined) {
    metrics.time_to_connect_ms = timeToConnectMs;
  }
  return { timestamp: Date.now(), metrics };
}

type ConnectionStatsExtractor = (report: RTCStatsReport) => ConnectionStats;

/**
 * `lib.dom`'s own `RTCStats` only has `id`/`timestamp`/`type` — the fields
 * below are the real, spec-defined ones this extractor reads off whichever
 * concrete stat type each belongs to (`RTCIceCandidatePairStats`,
 * `RTCIceCandidateStats`, `RTCInboundRtpStreamStats`, `RTCOutboundRtpStreamStats`,
 * `RTCRemoteInboundRtpStreamStats`). `forEach()`'s callback
 * is typed `any` in `lib.dom`, so annotating it with this instead is what
 * gets every access below out from under `no-unsafe-member-access`.
 *
 * Also fills in `get()` — Map-like lookup by id (e.g. a candidate-pair's
 * `localCandidateId`) is how the spec's `RTCStatsReport` actually behaves,
 * but `lib.dom` doesn't declare it either.
 */
interface RTCStatsReportEntry extends RTCStats {
  state?: string;
  nominated?: boolean;
  selectedCandidatePairId?: string;
  selected?: boolean;
  currentRoundTripTime?: number;
  availableOutgoingBitrate?: number;
  availableIncomingBitrate?: number;
  localCandidateId?: string;
  bytesReceived?: number;
  bytesSent?: number;
  candidateType?: string;
  kind?: string;
  framesPerSecond?: number;
  jitter?: number;
  packetsReceived?: number;
  packetsLost?: number;
  mid?: string;
  framesDecoded?: number;
  framesDropped?: number;
  frameWidth?: number;
  frameHeight?: number;
  nackCount?: number;
  firCount?: number;
  pliCount?: number;
  codecId?: string;
  mimeType?: string;
  localId?: string;
  roundTripTime?: number;
  packetsSent?: number;
  retransmittedPacketsSent?: number;
  framesEncoded?: number;
  concealedSamples?: number;
  totalSamplesReceived?: number;
}

interface RTCStatsReportWithLookup extends RTCStatsReport {
  get(id: string): RTCStatsReportEntry | undefined;
}

/**
 * The candidate-pair carrying the connection's media. Several pairs can be
 * `succeeded` and `nominated` at once (one per local interface), and only the
 * one the transport actually uses has a live round-trip time, a bandwidth
 * estimate and moving byte counters. In order of preference:
 *
 * 1. the pair the `transport` stat names (Chrome, Safari),
 * 2. the pair flagged `selected` (Firefox, which has no `transport` stat),
 * 3. the first `succeeded` and `nominated` pair, for a browser that marks
 *    neither.
 */
function activeCandidatePair(report: RTCStatsReport): RTCStatsReportEntry | undefined {
  let namedByTransport: string | undefined;
  let flaggedSelected: RTCStatsReportEntry | undefined;
  let firstNominated: RTCStatsReportEntry | undefined;

  report.forEach((stat: RTCStatsReportEntry) => {
    if (stat.type === 'transport' && stat.selectedCandidatePairId) {
      namedByTransport ??= stat.selectedCandidatePairId;
    }
    if (stat.type === 'candidate-pair') {
      if (stat.selected) {
        flaggedSelected ??= stat;
      }
      if (stat.state === 'succeeded' && stat.nominated) {
        firstNominated ??= stat;
      }
    }
  });

  const named =
    namedByTransport !== undefined ? (report as RTCStatsReportWithLookup).get(namedByTransport) : undefined;

  return named ?? flaggedSelected ?? firstNominated;
}

/**
 * A summary of the whole connection: the `ConnectionStats` behind
 * `getStats()` and `statsUpdate`. Connection-wide values come from the active
 * candidate-pair (see `activeCandidatePair()`); frame rate, jitter and loss
 * from the first received video stream.
 *
 * A closure over the previous reading's candidate-pair byte counters, needed
 * to turn them into a bitrate — so each poller keeps its own instance.
 */
export function createConnectionStatsExtractor(): ConnectionStatsExtractor {
  let lastBytesReceived: number | undefined;
  let lastBytesSent: number | undefined;
  let lastCandPairTimestamp: number | undefined;
  // An ICE restart or failover nominates a different candidate-pair, whose
  // byte counters start from their own, unrelated baseline — diffing against
  // the previous pair's counters would produce a bogus (often negative)
  // bitrate for that one sample.
  let lastCandPairId: string | undefined;

  return (report: RTCStatsReport) => {
    let rtt: number | undefined;
    let availableOutgoingBitrate: number | undefined;
    let availableIncomingBitrate: number | undefined;
    let incomingBitrate: number | undefined;
    let outgoingBitrate: number | undefined;
    let videoInboundRtpId: string | undefined;
    let framesPerSecond: number | undefined;
    let jitter: number | undefined;
    let packetLossRatio: number | undefined;
    let candidateType: string | undefined;

    const pair = activeCandidatePair(report);

    if (pair !== undefined) {
      if (pair.currentRoundTripTime !== undefined) {
        rtt = pair.currentRoundTripTime * 1000;
      }
      availableOutgoingBitrate = pair.availableOutgoingBitrate;
      availableIncomingBitrate = pair.availableIncomingBitrate;

      const localCandidate =
        pair.localCandidateId !== undefined
          ? (report as RTCStatsReportWithLookup).get(pair.localCandidateId)
          : undefined;

      if (localCandidate?.candidateType) {
        candidateType = localCandidate.candidateType;
      }
      const samePair = lastCandPairId === pair.id;
      const timeDiff: number =
        samePair && lastCandPairTimestamp !== undefined ? pair.timestamp - lastCandPairTimestamp : 0;

      if (pair.bytesReceived !== undefined) {
        if (samePair && lastBytesReceived !== undefined && timeDiff > 0) {
          incomingBitrate = (((pair.bytesReceived - lastBytesReceived) * 8) / timeDiff) * 1000; /* Bits/Second */
        }
        lastBytesReceived = pair.bytesReceived;
      }
      if (pair.bytesSent !== undefined) {
        if (samePair && lastBytesSent !== undefined && timeDiff > 0) {
          outgoingBitrate = (((pair.bytesSent - lastBytesSent) * 8) / timeDiff) * 1000; /* Bits/Second */
        }
        lastBytesSent = pair.bytesSent;
      }
      lastCandPairTimestamp = pair.timestamp;
      lastCandPairId = pair.id;
    }

    report.forEach((stat: RTCStatsReportEntry) => {
      // If there is more than one video stream the stats will be from the first one encountered.
      if (videoInboundRtpId === undefined && stat.type === 'inbound-rtp' && stat.kind === 'video') {
        videoInboundRtpId = stat.id;
        if (stat.framesPerSecond !== undefined) {
          framesPerSecond = stat.framesPerSecond;
        }
        if (stat.jitter !== undefined) {
          jitter = stat.jitter;
        }
        if (
          stat.packetsReceived !== undefined &&
          stat.packetsLost !== undefined &&
          stat.packetsReceived + stat.packetsLost > 0
        ) {
          packetLossRatio = stat.packetsLost / (stat.packetsReceived + stat.packetsLost);
        }
      }
    });

    return {
      rtt,
      candidateType,
      availableIncomingBitrate,
      availableOutgoingBitrate,
      incomingBitrate,
      outgoingBitrate,
      framesPerSecond,
      packetLossRatio,
      jitter,
      timestamp: Date.now(),
    };
  };
}
