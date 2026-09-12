package inc.reactor.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Wire snapshots from reactor-core/src/stats.rs. No rate arithmetic is repeated here.
 * Unknown summary measurements remain null; packetsLost is signed, including negatives.
 * Unsigned counters retain the complete uint64 range instead of passing through Double.
 */
data class ConnectionStats(
    val rttMs: Double?,
    val jitterS: Double?,
    val packetLossRatio: Double?,
    val incomingBitrateBps: Double?,
    val outgoingBitrateBps: Double?,
    val availableIncomingBitrateBps: Double?,
    val availableOutgoingBitrateBps: Double?,
    val targetBitrateBps: Double?,
    val framesPerSecond: Double?,
    val candidateType: String?,
    val relayProtocol: String?,
    val candidatePairState: String?,
    val packetsReceived: ULong,
    val packetsLost: Long,
    val packetsSent: ULong,
    val bytesReceived: ULong,
    val bytesSent: ULong,
    val timestampMs: Double,
    val inbound: List<InboundStats>,
    val outbound: List<OutboundStats>,
    val candidatePairs: List<CandidatePairStats>,
) {
    internal companion object {
        fun decode(value: JsonElement): ConnectionStats {
            val f = StatsFields(value)
            return ConnectionStats(
                rttMs = f.optional("rtt_ms") { f.number("rtt_ms") },
                jitterS = f.optional("jitter_s") { f.number("jitter_s") },
                packetLossRatio = f.optional("packet_loss_ratio") { f.number("packet_loss_ratio") },
                incomingBitrateBps = f.optional("incoming_bitrate_bps") { f.number("incoming_bitrate_bps") },
                outgoingBitrateBps = f.optional("outgoing_bitrate_bps") { f.number("outgoing_bitrate_bps") },
                availableIncomingBitrateBps = f.optional("available_incoming_bitrate_bps") { f.number("available_incoming_bitrate_bps") },
                availableOutgoingBitrateBps = f.optional("available_outgoing_bitrate_bps") { f.number("available_outgoing_bitrate_bps") },
                targetBitrateBps = f.optional("target_bitrate_bps") { f.number("target_bitrate_bps") },
                framesPerSecond = f.optional("frames_per_second") { f.number("frames_per_second") },
                candidateType = f.optional("candidate_type") { f.string("candidate_type") },
                relayProtocol = f.optional("relay_protocol") { f.string("relay_protocol") },
                candidatePairState = f.optional("candidate_pair_state") { f.string("candidate_pair_state") },
                packetsReceived = f.ulong("packets_received"),
                packetsLost = f.long("packets_lost"),
                packetsSent = f.ulong("packets_sent"),
                bytesReceived = f.ulong("bytes_received"),
                bytesSent = f.ulong("bytes_sent"),
                timestampMs = f.number("timestamp_ms"),
                inbound = f.list("inbound").map { InboundStats.decode(it) },
                outbound = f.list("outbound").map { OutboundStats.decode(it) },
                candidatePairs = f.list("candidate_pairs").map { CandidatePairStats.decode(it) },
            )
        }
    }
}

data class InboundStats(
    val ssrc: UInt,
    val kind: String?,
    val packetsReceived: UInt,
    val packetsLost: Int,
    val bytesReceived: ULong,
    val jitterS: Double,
    val nackCount: UInt,
    val totalDecodeTimeS: Double,
    val framesPerSecond: Double,
    val framesDecoded: UInt,
    val framesDropped: UInt,
    val frameWidth: UInt,
    val frameHeight: UInt,
) {
    internal companion object {
        fun decode(value: JsonElement): InboundStats {
            val f = StatsFields(value)
            return InboundStats(
                ssrc = f.uint("ssrc"),
                kind = f.optional("kind") { f.string("kind") },
                packetsReceived = f.uint("packets_received"),
                packetsLost = f.int("packets_lost"),
                bytesReceived = f.ulong("bytes_received"),
                jitterS = f.number("jitter_s"),
                nackCount = f.uint("nack_count"),
                totalDecodeTimeS = f.number("total_decode_time_s"),
                framesPerSecond = f.number("frames_per_second"),
                framesDecoded = f.uint("frames_decoded"),
                framesDropped = f.uint("frames_dropped"),
                frameWidth = f.uint("frame_width"),
                frameHeight = f.uint("frame_height"),
            )
        }
    }
}

data class OutboundStats(
    val ssrc: UInt,
    val kind: String?,
    val packetsSent: ULong,
    val retransmittedPacketsSent: ULong,
    val bytesSent: ULong,
    val targetBitrateBps: Double,
    val roundTripTimeS: Double,
    val totalRoundTripTimeS: Double,
    val fractionLost: Double,
    val packetsLost: Int,
    val framesPerSecond: Double,
    val framesSent: UInt,
    val frameWidth: UInt,
    val frameHeight: UInt,
) {
    internal companion object {
        fun decode(value: JsonElement): OutboundStats {
            val f = StatsFields(value)
            return OutboundStats(
                ssrc = f.uint("ssrc"),
                kind = f.optional("kind") { f.string("kind") },
                packetsSent = f.ulong("packets_sent"),
                retransmittedPacketsSent = f.ulong("retransmitted_packets_sent"),
                bytesSent = f.ulong("bytes_sent"),
                targetBitrateBps = f.number("target_bitrate_bps"),
                roundTripTimeS = f.number("round_trip_time_s"),
                totalRoundTripTimeS = f.number("total_round_trip_time_s"),
                fractionLost = f.number("fraction_lost"),
                packetsLost = f.int("packets_lost"),
                framesPerSecond = f.number("frames_per_second"),
                framesSent = f.uint("frames_sent"),
                frameWidth = f.uint("frame_width"),
                frameHeight = f.uint("frame_height"),
            )
        }
    }
}

data class CandidatePairStats(
    val currentRoundTripTimeS: Double,
    val totalRoundTripTimeS: Double,
    val priority: ULong,
    val state: String,
    val nominated: Boolean,
    val writable: Boolean,
    val availableOutgoingBitrateBps: Double,
    val availableIncomingBitrateBps: Double,
    val bytesSent: ULong,
    val bytesReceived: ULong,
    val packetsSent: ULong,
    val packetsReceived: ULong,
    val localCandidateType: String?,
    val localRelayProtocol: String?,
) {
    internal companion object {
        fun decode(value: JsonElement): CandidatePairStats {
            val f = StatsFields(value)
            return CandidatePairStats(
                currentRoundTripTimeS = f.number("current_round_trip_time_s"),
                totalRoundTripTimeS = f.number("total_round_trip_time_s"),
                priority = f.ulong("priority"),
                state = f.string("state"),
                nominated = f.boolean("nominated"),
                writable = f.boolean("writable"),
                availableOutgoingBitrateBps = f.number("available_outgoing_bitrate_bps"),
                availableIncomingBitrateBps = f.number("available_incoming_bitrate_bps"),
                bytesSent = f.ulong("bytes_sent"),
                bytesReceived = f.ulong("bytes_received"),
                packetsSent = f.ulong("packets_sent"),
                packetsReceived = f.ulong("packets_received"),
                localCandidateType = f.optional("local_candidate_type") { f.string("local_candidate_type") },
                localRelayProtocol = f.optional("local_relay_protocol") { f.string("local_relay_protocol") },
            )
        }
    }
}

internal fun connectionStats(value: JsonElement?): ConnectionStats = ConnectionStats.decode(requireNotNull(value) { "Missing statistics" })

private class StatsFields(
    value: JsonElement,
) {
    private val fields = value as? JsonObject ?: error("Statistics must be an object")

    private fun field(key: String): JsonElement = requireNotNull(fields[key]) { "Missing statistics field $key" }

    private fun primitive(key: String): JsonPrimitive = field(key) as? JsonPrimitive ?: error("$key must be a scalar")

    private fun numeric(key: String): String =
        primitive(key)
            .also {
                require(!it.isString && it != JsonNull) { "$key must be numeric" }
            }.content

    fun uint(key: String): UInt = requireNotNull(numeric(key).toUIntOrNull()) { "$key must fit uint32" }

    fun ulong(key: String): ULong = requireNotNull(numeric(key).toULongOrNull()) { "$key must fit uint64" }

    fun int(key: String): Int = requireNotNull(numeric(key).toIntOrNull()) { "$key must fit int32" }

    fun long(key: String): Long = requireNotNull(numeric(key).toLongOrNull()) { "$key must fit int64" }

    fun number(key: String): Double = requireNotNull(numeric(key).toDoubleOrNull()?.takeIf { it.isFinite() }) { "$key must be finite" }

    fun string(key: String): String = primitive(key).also { require(it.isString) { "$key must be a string" } }.content

    fun boolean(key: String): Boolean = requireNotNull(primitive(key).takeUnless { it.isString }?.booleanOrNull) { "$key must be boolean" }

    fun list(key: String): JsonArray = field(key) as? JsonArray ?: error("$key must be an array")

    fun <T> optional(
        key: String,
        read: () -> T,
    ): T? = if (field(key) == JsonNull) null else read()
}
