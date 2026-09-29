package com.torxone.app.transport.lora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/** GATT client for the TorX Radio firmware service. */
class BleTorXRadioLink(private val context: Context) : TorXRadioLink {
    override val method = RadioConnectionMethod.BLE
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingFrames: Flow<ByteArray> = incoming
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var helloChallenge: ByteArray? = null
    private var ready = CompletableDeferred<Unit>()
    private var hello = CompletableDeferred<TorXRadioCapabilities>()
    private var writeResult: CompletableDeferred<Boolean>? = null
    private val writeMutex = Mutex()

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(REQUIRED_GATT_MTU)) ready.completeExceptionally(error("Cannot request TorX Radio MTU"))
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED && !ready.isCompleted) ready.completeExceptionally(error("Radio disconnected"))
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < REQUIRED_GATT_MTU) {
                ready.completeExceptionally(error("TorX Radio requires GATT MTU $REQUIRED_GATT_MTU; negotiated $mtu"))
            } else gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { ready.completeExceptionally(error("GATT service discovery failed: $status")); return }
            val service = g.getService(TorXRadioProtocol.SERVICE_UUID)
                ?: run { ready.completeExceptionally(error("TorX Radio service missing")); return }
            tx = service.getCharacteristic(TorXRadioProtocol.PHONE_TO_RADIO_UUID)
                ?: run { ready.completeExceptionally(error("TorX Radio TX characteristic missing")); return }
            val rx = service.getCharacteristic(TorXRadioProtocol.RADIO_TO_PHONE_UUID)
                ?: run { ready.completeExceptionally(error("TorX Radio RX characteristic missing")); return }
            if (!g.setCharacteristicNotification(rx, true)) { ready.completeExceptionally(error("Cannot enable radio notifications")); return }
            val descriptor = rx.getDescriptor(CLIENT_CONFIGURATION_UUID)
                ?: run { ready.completeExceptionally(error("Radio notification descriptor missing")); return }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else {
                @Suppress("DEPRECATION")
                run { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE }
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
            else ready.completeExceptionally(error("Cannot subscribe to radio notifications: $status"))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = receive(value)
        @Deprecated("Deprecated by Android")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let(::receive)
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun receive(value: ByteArray) {
        val frame = runCatching { TorXRadioProtocol.decode(value) }.getOrNull() ?: return
        if (frame.kind == TorXRadioProtocol.Kind.HELLO_RESPONSE && !hello.isCompleted) {
            val challenge = helloChallenge ?: return
            runCatching { TorXRadioHelloCodec.decodeAndVerify(frame.payload, challenge) }
                .onSuccess(hello::complete).onFailure(hello::completeExceptionally)
        } else incoming.tryEmit(value.copyOf())
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(candidate: TorXRadioCandidate): TorXRadioCapabilities {
        val device = candidate.nativeHandle as? BluetoothDevice ?: error("BLE device handle missing")
        ready = CompletableDeferred(); hello = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
        val challenge = ByteArray(TorXRadioHelloCodec.CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        helloChallenge = challenge
        check(send(TorXRadioProtocol.encode(TorXRadioProtocol.Frame(TorXRadioProtocol.Kind.HELLO_REQUEST, 0, challenge)))) {
            "Radio rejected HELLO request"
        }
        return withTimeout(CONNECT_TIMEOUT_MS) { hello.await() }
    }

    @SuppressLint("MissingPermission")
    override suspend fun send(frame: ByteArray): Boolean = writeMutex.withLock {
        val g = gatt ?: return false
        val characteristic = tx ?: return false
        val result = CompletableDeferred<Boolean>().also { writeResult = it }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { characteristic.value = frame }
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
        if (!started) { writeResult = null; return false }
        return runCatching { withTimeout(WRITE_TIMEOUT_MS) { result.await() } }.getOrDefault(false).also { writeResult = null }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() { gatt?.disconnect(); gatt?.close(); gatt = null; tx = null }

    companion object {
        private val CLIENT_CONFIGURATION_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val REQUIRED_GATT_MTU = 499
    }
}
