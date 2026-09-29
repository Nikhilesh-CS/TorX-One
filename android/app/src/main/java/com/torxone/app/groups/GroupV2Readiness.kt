package com.torxone.app.groups

/**
 * Fail-closed release gate for replacing pairwise Group V1 with RFC 9420 MLS.
 * No feature flag may bypass these checks.
 */
data class GroupV2Evidence(
    val rfc9420ImplementationIntegrated: Boolean = false,
    val androidNativeBuildReproducible: Boolean = false,
    val torxIdentityCredentialBindingVerified: Boolean = false,
    val encryptedAtomicStatePersistenceVerified: Boolean = false,
    val crashRecoveryVectorsPassed: Boolean = false,
    val rfcInteroperabilityVectorsPassed: Boolean = false,
    val addRemoveUpdateCommitTestsPassed: Boolean = false,
    val offlineOutOfOrderDeliveryTestsPassed: Boolean = false,
    val downgradeAndForkTestsPassed: Boolean = false,
    val metadataAndAbuseReviewPassed: Boolean = false,
    val independentSecurityReviewPassed: Boolean = false,
    val physicalMultiDeviceAcceptancePassed: Boolean = false
)

data class GroupV2ReadinessReport(val ready: Boolean, val missing: List<String>)

object GroupV2MigrationGate {
    const val REQUIRED_CIPHERSUITE = "MLS_128_DHKEMX25519_CHACHA20POLY1305_SHA256_Ed25519"
    const val PROTOCOL_VERSION = "MLS 1.0 / RFC 9420"

    fun evaluate(evidence: GroupV2Evidence): GroupV2ReadinessReport {
        val checks = linkedMapOf(
            "RFC 9420 implementation" to evidence.rfc9420ImplementationIntegrated,
            "reproducible Android native build" to evidence.androidNativeBuildReproducible,
            "TorX identity credential binding" to evidence.torxIdentityCredentialBindingVerified,
            "encrypted atomic MLS state persistence" to evidence.encryptedAtomicStatePersistenceVerified,
            "crash recovery vectors" to evidence.crashRecoveryVectorsPassed,
            "RFC interoperability vectors" to evidence.rfcInteroperabilityVectorsPassed,
            "add/remove/update/commit coverage" to evidence.addRemoveUpdateCommitTestsPassed,
            "offline and out-of-order delivery coverage" to evidence.offlineOutOfOrderDeliveryTestsPassed,
            "downgrade and fork coverage" to evidence.downgradeAndForkTestsPassed,
            "metadata and abuse review" to evidence.metadataAndAbuseReviewPassed,
            "independent security review" to evidence.independentSecurityReviewPassed,
            "physical multi-device acceptance" to evidence.physicalMultiDeviceAcceptancePassed
        )
        val missing = checks.filterValues { !it }.keys.toList()
        return GroupV2ReadinessReport(missing.isEmpty(), missing)
    }

    fun requireReady(evidence: GroupV2Evidence) {
        val report = evaluate(evidence)
        check(report.ready) { "Group V2 migration blocked: ${report.missing.joinToString()}" }
    }
}

enum class GroupProtocolGeneration { PAIRWISE_V1, MLS_V2 }

/** Explicit negotiation prevents a peer from silently downgrading an established MLS group. */
data class GroupProtocolPolicy(
    val establishedGeneration: GroupProtocolGeneration,
    val minimumAcceptedGeneration: GroupProtocolGeneration = establishedGeneration
) {
    fun accepts(offered: GroupProtocolGeneration): Boolean = offered.ordinal >= minimumAcceptedGeneration.ordinal
}
