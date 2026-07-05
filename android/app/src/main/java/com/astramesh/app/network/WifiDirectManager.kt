package com.astramesh.app.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WifiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectManager"
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _isWifiDirectEnabled = MutableStateFlow(false)
    val isWifiDirectEnabled: StateFlow<Boolean> = _isWifiDirectEnabled.asStateFlow()

    private val _peers = MutableStateFlow<List<android.net.wifi.p2p.WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<android.net.wifi.p2p.WifiP2pDevice>> = _peers.asStateFlow()

    init {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        manager?.let {
            channel = it.initialize(context, context.mainLooper, null)
            setupReceiver()
        }
    }

    private fun setupReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        _isWifiDirectEnabled.value = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager?.requestPeers(channel) { peerList ->
                            _peers.value = peerList.deviceList.toList()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            manager?.requestConnectionInfo(channel) { info ->
                                // Connection established. Group owner IP available.
                                Log.d(TAG, "Wi-Fi Direct Connection established. Group Owner IP: ${info.groupOwnerAddress?.hostAddress}")
                            }
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, intentFilter)
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (!_isWifiDirectEnabled.value) return
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery Initiated")
            }
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Discovery Failed: $reasonCode")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String, onSuccess: () -> Unit, onFailure: () -> Unit) {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection Initiated")
                onSuccess()
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connection Failed: $reason")
                onFailure()
            }
        })
    }

    fun cleanup() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }
}
