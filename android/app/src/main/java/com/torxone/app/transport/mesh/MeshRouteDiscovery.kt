package com.torxone.app.transport.mesh

import com.torxone.app.identity.IdentityCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

data class MeshPeer(
    val nodeId: String,
    val relationshipId: String,
    val sendQueueAddress: String,
    val signingPublicKey: ByteArray,
    val relayAllowed: Boolean
)

class MeshPeerDirectory {
    private val peers = ConcurrentHashMap<String, MeshPeer>()
    fun authenticated(peer: MeshPeer) {
        require(peer.nodeId.isNotBlank() && peer.relationshipId.isNotBlank() && peer.signingPublicKey.size == 32)
        peers[peer.nodeId] = peer.copy(signingPublicKey = peer.signingPublicKey.copyOf())
    }
    fun disconnected(nodeId: String) { peers.remove(nodeId) }
    fun get(nodeId: String): MeshPeer? = peers[nodeId]
    fun all(): List<MeshPeer> = peers.values.toList()
}

data class MeshRouteAnnouncement(
    val destinationNodeId: String,
    val sequence: Long,
    val advertisedHopCount: Int,
    val expiresAt: Long,
    val signature: ByteArray
)

object MeshRouteAnnouncementCodec {
    private const val MAGIC = 0x54585241 // TXRA
    private const val MAX_BYTES = 512

    fun isAnnouncement(bytes: ByteArray): Boolean =
        bytes.size >= 4 && ByteBuffer.wrap(bytes, 0, 4).int == MAGIC

    fun signedBytes(destinationNodeId: String, sequence: Long, expiresAt: Long): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeUTF("TorX-Mesh-Route-V1")
                data.writeUTF(destinationNodeId)
                data.writeLong(sequence)
                data.writeLong(expiresAt)
            }
            output.toByteArray()
        }

    fun encode(value: MeshRouteAnnouncement): ByteArray {
        require(value.destinationNodeId.length in 1..128 && value.sequence >= 0)
        require(value.advertisedHopCount in 0 until MeshPacketCodec.MAX_TTL)
        require(value.signature.size == 64)
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeUTF(value.destinationNodeId)
                data.writeLong(value.sequence)
                data.writeByte(value.advertisedHopCount)
                data.writeLong(value.expiresAt)
                data.write(value.signature)
            }
            output.toByteArray()
        }.also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): MeshRouteAnnouncement {
        require(bytes.size in 1..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC)
            val destination = data.readUTF()
            val sequence = data.readLong()
            val hops = data.readUnsignedByte()
            val expires = data.readLong()
            require(data.available() == 64)
            val signature = ByteArray(64)
            data.readFully(signature)
            MeshRouteAnnouncement(destination, sequence, hops, expires, signature)
        }
    }
}

/** Signed distance-vector discovery with per-peer rate limiting and split horizon. */
class MeshRouteDiscovery(
    private val routingTable: MeshRoutingTable,
    private val peerDirectory: MeshPeerDirectory,
    private val publicKeyForNode: (String) -> ByteArray?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAnnouncementsPerMinutePerPeer: Int = 120
) {
    private data class RateWindow(var startedAt: Long, var count: Int)
    private val rateWindows = ConcurrentHashMap<String, RateWindow>()
    private val signedAnnouncements = ConcurrentHashMap<String, MeshRouteAnnouncement>()

    fun createLocalAnnouncement(
        localNodeId: String,
        sequence: Long,
        expiresAt: Long,
        signingPrivateKey: ByteArray
    ): MeshRouteAnnouncement {
        require(expiresAt > clock())
        val signature = IdentityCrypto.signEd25519(
            signingPrivateKey,
            MeshRouteAnnouncementCodec.signedBytes(localNodeId, sequence, expiresAt)
        )
        return MeshRouteAnnouncement(localNodeId, sequence, 0, expiresAt, signature).also {
            signedAnnouncements[localNodeId] = it
        }
    }

    fun receive(fromPeerNodeId: String, announcement: MeshRouteAnnouncement): Boolean {
        val peer = peerDirectory.get(fromPeerNodeId) ?: return false
        if (!allowRate(fromPeerNodeId)) return false
        val now = clock()
        if (announcement.expiresAt <= now || announcement.expiresAt - now > MAX_ROUTE_LIFETIME_MS) return false
        if (announcement.destinationNodeId == fromPeerNodeId && announcement.advertisedHopCount != 0) return false
        val publicKey = publicKeyForNode(announcement.destinationNodeId)
            ?: if (announcement.destinationNodeId == peer.nodeId) peer.signingPublicKey else return false
        val valid = IdentityCrypto.verifyEd25519(
            publicKey,
            MeshRouteAnnouncementCodec.signedBytes(
                announcement.destinationNodeId, announcement.sequence, announcement.expiresAt
            ),
            announcement.signature
        )
        if (!valid) return false
        val hopCount = announcement.advertisedHopCount + 1
        val installed = routingTable.installAuthenticated(
            MeshRoute(
                destinationNodeId = announcement.destinationNodeId,
                nextHopNodeId = fromPeerNodeId,
                hopCount = hopCount,
                sequence = announcement.sequence,
                expiresAt = announcement.expiresAt,
                learnedFromNodeId = fromPeerNodeId
            )
        )
        if (installed) signedAnnouncements[announcement.destinationNodeId] = announcement.copy()
        return installed
    }

    fun announcementsForPeer(peerNodeId: String): List<MeshRouteAnnouncement> =
        routingTable.snapshot()
            .filter { it.learnedFromNodeId != peerNodeId && it.nextHopNodeId != peerNodeId }
            .mapNotNull { route -> signedAnnouncements[route.destinationNodeId]?.copy(advertisedHopCount = route.hopCount) }

    private fun allowRate(peerNodeId: String): Boolean {
        val now = clock()
        val window = rateWindows.compute(peerNodeId) { _, current ->
            if (current == null || now - current.startedAt >= 60_000) RateWindow(now, 1)
            else current.apply { count++ }
        }!!
        return window.count <= maxAnnouncementsPerMinutePerPeer
    }

    companion object { const val MAX_ROUTE_LIFETIME_MS = 10 * 60_000L }
}
