package com.torxone.app.scalability

import java.util.Random
import kotlin.math.max

data class ScaleWorkload(
    val contacts: Int = 1_000, val messages: Int = 100_000,
    val activeRelationships: Int = 1_000, val mediaItems: Int = 10_000,
    val groupMembers: Int = 64, val meshNodes: Int = 2_048,
    val meshPackets: Int = 20_000, val churnPercent: Int = 15,
    val linkLossPercent: Int = 2, val payloadBytes: Int = 1_024
) {
    init {
        require(contacts >= 1_000 && messages >= 100_000)
        require(activeRelationships in 1..contacts)
        require(mediaItems >= 0 && groupMembers in 2..64)
        require(meshNodes >= 1_000 && meshPackets > 0)
        require(churnPercent in 0..90 && linkLossPercent in 0..90)
        require(payloadBytes > 0)
    }
}

data class StorageProjection(val databaseBytes: Long, val encryptedMediaBytes: Long, val totalBytes: Long)

/** Conservative planning model. Media payloads stay outside Room. */
object ScaleStorageEstimator {
    fun project(workload: ScaleWorkload, averageEncryptedMediaBytes: Long = 512L * 1024L): StorageProjection {
        require(averageEncryptedMediaBytes >= 0)
        val database = Math.addExact(
            Math.addExact(workload.contacts * 2_048L, workload.messages * 2_560L),
            Math.addExact(workload.activeRelationships * 4_096L, workload.mediaItems * 1_024L)
        )
        val media = Math.multiplyExact(workload.mediaItems.toLong(), averageEncryptedMediaBytes)
        return StorageProjection(database, media, Math.addExact(database, media))
    }
}

data class MeshScaleMetrics(
    val nodes: Int, val attemptedPackets: Int, val deliveredPackets: Int,
    val lossPercent: Double, val averageHops: Double, val p95Hops: Int,
    val payloadBytesDelivered: Long, val routingControlBytes: Long,
    val routingOverheadRatio: Double, val elapsedNanos: Long, val estimatedOperations: Long
)

/** Reproducible small-world simulation with the production 16-hop mesh ceiling. */
class DeterministicMeshSimulator(private val seed: Long = 0x544f5258L) {
    fun run(workload: ScaleWorkload): MeshScaleMetrics {
        val random = Random(seed)
        val adjacency = Array(workload.meshNodes) { node ->
            intArrayOf(-257, -67, -17, -1, 1, 17, 67, 257)
                .map { (node + workload.meshNodes + it) % workload.meshNodes }
                .distinct()
                .toIntArray()
        }
        val online = BooleanArray(workload.meshNodes) { random.nextInt(100) >= workload.churnPercent }
        val distances = IntArray(workload.meshNodes)
        val queue = IntArray(workload.meshNodes)
        val hopSamples = IntArray(workload.meshPackets)
        var delivered = 0
        var operations = 0L
        val started = System.nanoTime()
        repeat(workload.meshPackets) {
            val source = random.nextInt(workload.meshNodes)
            var destination = random.nextInt(workload.meshNodes)
            if (destination == source) destination = (destination + 1) % workload.meshNodes
            val result = shortestPath(source, destination, online, adjacency, distances, queue)
            operations += result.second
            val hops = result.first
            if (hops in 1..16 && survivesLinks(hops, workload.linkLossPercent, random)) hopSamples[delivered++] = hops
        }
        val elapsed = System.nanoTime() - started
        val sortedHops = hopSamples.copyOf(delivered).apply { sort() }
        val payloadDelivered = delivered.toLong() * workload.payloadBytes
        val controlBytes = online.count { it }.toLong() * 8L * 160L
        return MeshScaleMetrics(
            workload.meshNodes, workload.meshPackets, delivered,
            100.0 * (workload.meshPackets - delivered) / workload.meshPackets,
            if (delivered == 0) 0.0 else sortedHops.sumOf { it.toLong() }.toDouble() / delivered,
            if (delivered == 0) 0 else sortedHops[max(0, (delivered * 95 / 100) - 1)],
            payloadDelivered, controlBytes,
            if (payloadDelivered == 0L) Double.POSITIVE_INFINITY else controlBytes.toDouble() / payloadDelivered,
            elapsed, operations
        )
    }

    private fun shortestPath(source: Int, destination: Int, online: BooleanArray, adjacency: Array<IntArray>, distances: IntArray, queue: IntArray): Pair<Int, Long> {
        if (!online[source] || !online[destination]) return -1 to 0L
        java.util.Arrays.fill(distances, -1)
        var head = 0; var tail = 0; var operations = 0L
        queue[tail++] = source; distances[source] = 0
        while (head < tail) {
            val node = queue[head++]; val nextDistance = distances[node] + 1
            if (nextDistance > 16) continue
            for (neighbor in adjacency[node]) {
                operations++
                if (!online[neighbor] || distances[neighbor] >= 0) continue
                distances[neighbor] = nextDistance
                if (neighbor == destination) return nextDistance to operations
                queue[tail++] = neighbor
            }
        }
        return -1 to operations
    }

    private fun survivesLinks(hops: Int, lossPercent: Int, random: Random): Boolean {
        repeat(hops) { if (random.nextInt(100) < lossPercent) return false }
        return true
    }
}

data class Phase15Acceptance(val passed: Boolean, val failures: List<String>)

object Phase15Gate {
    fun evaluate(workload: ScaleWorkload, metrics: MeshScaleMetrics): Phase15Acceptance {
        val failures = buildList {
            if (workload.contacts < 1_000) add("contacts")
            if (workload.messages < 100_000) add("messages")
            if (metrics.nodes < 1_000) add("mesh nodes")
            if (metrics.deliveredPackets == 0) add("delivery")
            if (metrics.p95Hops !in 1..16) add("routing hop bound")
            if (!metrics.routingOverheadRatio.isFinite()) add("routing overhead")
        }
        return Phase15Acceptance(failures.isEmpty(), failures)
    }
}
