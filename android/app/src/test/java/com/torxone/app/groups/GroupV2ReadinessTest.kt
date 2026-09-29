package com.torxone.app.groups

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupV2ReadinessTest {
    @Test fun `migration is fail closed until every release gate passes`() {
        val report = GroupV2MigrationGate.evaluate(GroupV2Evidence(
            rfc9420ImplementationIntegrated = true,
            androidNativeBuildReproducible = true
        ))
        assertFalse(report.ready)
        assertTrue("independent security review" in report.missing)
        assertTrue("physical multi-device acceptance" in report.missing)
    }

    @Test fun `complete evidence opens migration gate`() {
        val complete = GroupV2Evidence(true, true, true, true, true, true, true, true, true, true, true, true)
        assertTrue(GroupV2MigrationGate.evaluate(complete).ready)
        GroupV2MigrationGate.requireReady(complete)
    }

    @Test fun `established MLS group rejects pairwise downgrade`() {
        val policy = GroupProtocolPolicy(GroupProtocolGeneration.MLS_V2)
        assertTrue(policy.accepts(GroupProtocolGeneration.MLS_V2))
        assertFalse(policy.accepts(GroupProtocolGeneration.PAIRWISE_V1))
    }
}
