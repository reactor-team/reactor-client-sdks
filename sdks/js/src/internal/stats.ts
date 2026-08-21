import type { ConnectionStats } from '../types';

/** How often `Reactor` samples `getPeerConnection().getStats()` while ready. */
export const STATS_INTERVAL_MS = 2_000;

type RTCStatsExtractor = (report: RTCStatsReport) => ConnectionStats;

/**
 * `lib.dom`'s own `RTCStats` only has `id`/`timestamp`/`type` — the fields
 * below are the real, spec-defined ones this extractor reads off whichever
 * concrete stat type each belongs to (`RTCIceCandidatePairStats`,
 * `RTCIceCandidateStats`, `RTCInboundRtpStreamStats`). `forEach()`'s callback
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
}

interface RTCStatsReportWithLookup extends RTCStatsReport {
  get(id: string): RTCStatsReportEntry | undefined;
}

/**
 * A closure over the previous sample's byte counters and timestamp, needed
 * to turn the peer connection's cumulative candidate-pair counters into a
 * real-time bitrate.
 */
export function createRTCStatsExtractor(): RTCStatsExtractor {
  let lastBytesReceived: number | undefined;
  let lastBytesSent: number | undefined;
  let lastCandPairTimestamp: number | undefined;
  // An ICE restart or failover nominates a different candidate-pair, whose
  // byte counters start from their own, unrelated baseline — diffing against
  // the previous pair's counters would produce a bogus (often negative)
  // bitrate for that one sample.
  let lastCandPairId: string | undefined;

  return (report: RTCStatsReport) => {
    let candPairId: string | undefined;
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

    const reportWithLookup = report as RTCStatsReportWithLookup;

    report.forEach((stat: RTCStatsReportEntry) => {
      if (
        candPairId === undefined &&
        stat.type === 'candidate-pair' &&
        stat.state === 'succeeded' &&
        stat.nominated
      ) {
        // Extract stats from the first successful candidate-pair found.
        candPairId = stat.id;
        if (stat.currentRoundTripTime !== undefined) {
          rtt = stat.currentRoundTripTime * 1000;
        }
        if (stat.availableOutgoingBitrate !== undefined) {
          availableOutgoingBitrate = stat.availableOutgoingBitrate;
        }
        if (stat.availableIncomingBitrate !== undefined) {
          availableIncomingBitrate = stat.availableIncomingBitrate;
        }
        const localCandidate =
          stat.localCandidateId !== undefined ? reportWithLookup.get(stat.localCandidateId) : undefined;

        if (localCandidate?.candidateType) {
          candidateType = localCandidate.candidateType;
        }
        const samePair = lastCandPairId === candPairId;
        const timeDiff: number =
          samePair && lastCandPairTimestamp !== undefined ? stat.timestamp - lastCandPairTimestamp : 0;

        if (stat.bytesReceived !== undefined) {
          if (samePair && lastBytesReceived !== undefined && timeDiff > 0) {
            incomingBitrate = (((stat.bytesReceived - lastBytesReceived) * 8) / timeDiff) * 1000; /* Bits/Second */
          }
          lastBytesReceived = stat.bytesReceived;
        }
        if (stat.bytesSent !== undefined) {
          if (samePair && lastBytesSent !== undefined && timeDiff > 0) {
            outgoingBitrate = (((stat.bytesSent - lastBytesSent) * 8) / timeDiff) * 1000; /* Bits/Second */
          }
          lastBytesSent = stat.bytesSent;
        }
        lastCandPairTimestamp = stat.timestamp;
        lastCandPairId = candPairId;
      }

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
