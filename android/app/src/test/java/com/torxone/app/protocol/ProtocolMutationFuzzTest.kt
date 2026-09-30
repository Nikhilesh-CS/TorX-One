package com.torxone.app.protocol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Reproducible bounded parser mutation smoke test; not coverage-guided fuzzing. */
class ProtocolMutationFuzzTest {
    @Test fun malformedFramesCannotEscapeAsFatalErrors() {
        val random = Random(1718)
        repeat(5000) {
            val input = ByteArray(random.nextInt(2048)).also(random::nextBytes)
            for (decode in listOf<(ByteArray) -> Any>(
                ProtocolCodec::decodeSecureEnvelope, ProtocolCodec::decodeTransportEnvelope
            )) {
                try { decode(input) } catch (expected: Exception) {
                    assertTrue(expected is IllegalArgumentException || expected is java.io.IOException)
                }
            }
        }
        val frame = ProtocolCodec.encodeTransportEnvelope(OpaqueTransportEnvelope(
            version = 1, envelopeId = "fuzz", queueAddress = "queue",
            opaqueCiphertext = byteArrayOf(1, 2, 3), queueAuthenticator = ByteArray(32)
        ))
        repeat(2000) {
            val mutated = frame.copyOf()
            mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte()
            try { ProtocolCodec.decodeTransportEnvelope(mutated) } catch (expected: Exception) {
                assertTrue(expected is IllegalArgumentException || expected is java.io.IOException)
            }
        }
    }
}
