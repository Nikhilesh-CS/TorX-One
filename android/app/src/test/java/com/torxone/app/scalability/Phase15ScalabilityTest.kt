package com.torxone.app.scalability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase15ScalabilityTest {
    @Test fun `large data workload uses checked long storage accounting`() {
        val result = ScaleStorageEstimator.project(ScaleWorkload())
        assertTrue(result.databaseBytes > 250L * 1024L * 1024L)
        assertTrue(result.encryptedMediaBytes > 4L * 1024L * 1024L * 1024L)
        assertEquals(result.databaseBytes + result.encryptedMediaBytes, result.totalBytes)
    }

    @Test fun `two thousand node mesh remains bounded under churn and loss`() {
        val workload = ScaleWorkload()
        val metrics = DeterministicMeshSimulator().run(workload)
        val gate = Phase15Gate.evaluate(workload, metrics)
        println("PHASE15_METRICS=$metrics")
        assertTrue(gate.failures.joinToString(), gate.passed)
        assertEquals(workload.meshPackets, metrics.attemptedPackets)
        assertTrue(metrics.deliveredPackets > workload.meshPackets / 2)
        assertTrue(metrics.p95Hops in 1..16)
        assertTrue(metrics.routingOverheadRatio < 0.25)
        assertTrue(metrics.estimatedOperations < 500_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subscale workloads cannot be phase 15 evidence`() { ScaleWorkload(contacts = 999) }
}
