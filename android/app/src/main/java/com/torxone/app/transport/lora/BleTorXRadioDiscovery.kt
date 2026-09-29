package com.torxone.app.transport.lora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Discovers only peripherals advertising the TorX Radio service UUID. */
class BleTorXRadioDiscovery(context: Context) : TorXRadioDiscovery {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = MutableSharedFlow<TorXRadioCandidate>(extraBufferCapacity = 16)
    override val candidates: Flow<TorXRadioCandidate> = found
    private var scanning = false
    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            found.tryEmit(TorXRadioCandidate(
                stableId = result.device.address,
                displayName = result.scanRecord?.deviceName,
                method = RadioConnectionMethod.BLE,
                advertisedService = TorXRadioProtocol.SERVICE_UUID,
                nativeHandle = result.device
            ))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        val bluetooth = adapter ?: error("Bluetooth LE is unavailable")
        require(bluetooth.isEnabled) { "Bluetooth is disabled" }
        if (scanning) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(TorXRadioProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetooth.bluetoothLeScanner?.startScan(listOf(filter), settings, callback)
            ?: error("Bluetooth LE scanner unavailable")
        scanning = true
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop() {
        if (scanning) adapter?.bluetoothLeScanner?.stopScan(callback)
        scanning = false
    }
}
