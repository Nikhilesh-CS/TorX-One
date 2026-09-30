package com.torxone.app.transport.halow

import android.net.Network
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Length-aware TCP client. Uses the Android Network belonging to the discovered local gateway when available. */
class TcpHaLowGatewayLink : HaLowGatewayLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    override val incomingFrames: Flow<ByteArray> = incoming
    private val writeMutex = Mutex()
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var readerJob: Job? = null
    private var challenge: ByteArray? = null
    private var hello = CompletableDeferred<HaLowGatewayCapabilities>()

    override suspend fun connect(candidate: HaLowGatewayCandidate): HaLowGatewayCapabilities {
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            candidate.networkHandle?.let(Network::fromNetworkHandle)
        } else null
        val connected = network?.socketFactory?.createSocket() ?: Socket()
        connected.connect(InetSocketAddress(candidate.host, candidate.port), CONNECT_TIMEOUT_MS.toInt())
        connected.tcpNoDelay = true
        socket = connected
        output = DataOutputStream(connected.getOutputStream())
        hello = CompletableDeferred()
        readerJob = scope.launch { readLoop(DataInputStream(connected.getInputStream())) }
        val nonce = ByteArray(HaLowGatewayHelloCodec.CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        challenge = nonce
        check(send(HaLowGatewayProtocol.encode(HaLowGatewayProtocol.Frame(HaLowGatewayProtocol.Kind.HELLO_REQUEST, 0, nonce))))
        return withTimeout(CONNECT_TIMEOUT_MS) { hello.await() }
    }

    private suspend fun readLoop(input: DataInputStream) {
        try {
            while (true) {
                val header = ByteArray(16); input.readFully(header)
                val payloadLength = java.nio.ByteBuffer.wrap(header, 8, 4).int
                require(payloadLength in 0..HaLowGatewayProtocol.MAX_PAYLOAD_BYTES)
                val tail = ByteArray(payloadLength + 4); input.readFully(tail)
                val bytes = header + tail
                val frame = HaLowGatewayProtocol.decode(bytes)
                if (frame.kind == HaLowGatewayProtocol.Kind.HELLO_RESPONSE && !hello.isCompleted) {
                    val nonce = challenge ?: error("Missing gateway challenge")
                    hello.complete(HaLowGatewayHelloCodec.decodeAndVerify(frame.payload, nonce))
                } else incoming.emit(bytes)
            }
        } catch (error: Throwable) {
            if (!hello.isCompleted) hello.completeExceptionally(error)
        }
    }

    override suspend fun send(frame: ByteArray): Boolean = writeMutex.withLock {
        val stream = output ?: return false
        runCatching { stream.write(frame); stream.flush() }.isSuccess
    }

    override suspend fun disconnect() {
        readerJob?.cancel(); readerJob = null
        runCatching { socket?.close() }; socket = null; output = null
        scope.cancel()
    }

    companion object { private const val CONNECT_TIMEOUT_MS = 15_000L }
}
