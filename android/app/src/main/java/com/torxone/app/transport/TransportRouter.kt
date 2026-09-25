package com.torxone.app.transport

import android.util.Log
import com.torxone.app.protocol.OpaqueTransportEnvelope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TransportRouter — selects and fails over between available transports.
 *
 * This is the ONLY networking component TorXAgent talks to.
 * It uses TransportPolicy to pick routes, not hardcoded logic.
 */
class TransportRouter {
    companion object {
        private const val TAG = "TransportRouter"
    }

    private val transports = mutableMapOf<String, Transport>()
    private val mutex = Mutex()
    private var incomingHandler: ((OpaqueTransportEnvelope) -> Unit)? = null

    /**
     * Register a transport. Called at startup.
     */
    fun registerTransport(name: String, transport: Transport) {
        transports[name] = transport
        transport.setIncomingHandler { envelope ->
            incomingHandler?.invoke(envelope)
        }
        Log.i(TAG, "Registered transport: $name")
    }

    /**
     * Set the handler for incoming envelopes from any transport.
     */
    fun setIncomingHandler(handler: (OpaqueTransportEnvelope) -> Unit) {
        incomingHandler = handler
        // Update all registered transports
        transports.values.forEach { it.setIncomingHandler(handler) }
    }

    /**
     * Send an envelope using the best available transport.
     * Tries transports in policy-defined priority order.
     * Fails over automatically.
     */
    suspend fun send(
        envelope: OpaqueTransportEnvelope,
        connectionId: String
    ): TransportResult {
        val available = getAvailableTransportNames()
        val policy = TransportPolicy(availableTransports = available)
        val prioritized = policy.prioritizedTransports()

        if (prioritized.isEmpty()) {
            return TransportResult.Failure("none", "No transports available")
        }

        val failures = mutableListOf<String>()

        for (transportName in prioritized) {
            val transport = transports[transportName] ?: continue

            Log.d(TAG, "Attempting delivery via $transportName for envelope ${envelope.envelopeId}")

            val result = try {
                transport.send(envelope, connectionId)
            } catch (e: Exception) {
                Log.w(TAG, "Transport $transportName threw exception", e)
                TransportResult.Failure(transportName, e.message ?: "Unknown error")
            }

            when (result) {
                is TransportResult.Success -> {
                    Log.d(TAG, "Delivery succeeded via $transportName")
                    return result
                }
                is TransportResult.Failure -> {
                    Log.d(TAG, "Transport $transportName failed: ${result.reason}")
                    failures.add("$transportName: ${result.reason}")
                }
            }
        }

        return TransportResult.Failure(
            "all",
            "All transports failed: ${failures.joinToString("; ")}"
        )
    }

    /**
     * Send an ACK via any available transport.
     */
    suspend fun sendAck(queueAddress: String, envelopeId: String): TransportResult {
        val available = getAvailableTransportNames()
        val policy = TransportPolicy(availableTransports = available)

        for (transportName in policy.prioritizedTransports()) {
            val transport = transports[transportName] ?: continue
            val result = transport.sendAck(queueAddress, envelopeId)
            if (result is TransportResult.Success) return result
        }

        return TransportResult.Failure("all", "No transport could send ACK")
    }

    /**
     * Start all registered transports.
     */
    suspend fun startAll() {
        transports.forEach { (name, transport) ->
            try {
                transport.start()
                Log.i(TAG, "Started transport: $name")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start transport: $name", e)
            }
        }
    }

    /**
     * Stop all transports.
     */
    suspend fun stopAll() {
        transports.forEach { (name, transport) ->
            try {
                transport.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping transport $name", e)
            }
        }
    }

    private suspend fun getAvailableTransportNames(): Set<String> {
        return transports.filter { (_, transport) ->
            try {
                transport.isAvailable()
            } catch (e: Exception) {
                false
            }
        }.keys
    }
}
