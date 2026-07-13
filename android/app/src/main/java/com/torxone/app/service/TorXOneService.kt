package com.torxone.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.torxone.app.R
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.data.AppDatabase
import com.torxone.app.identity.IdentityManager
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.NearbyConnectionManager
import com.torxone.app.network.TorManager
import com.torxone.app.network.TorState
import com.torxone.app.updater.GitHubUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Central service that owns all networking: Nearby, Tor, and MessageRouter.
 * Runs as a foreground service so connections survive Activity lifecycle changes.
 */
class TorXOneService : Service() {
    companion object {
        private const val TAG = "TorXOneService"
        private const val CHANNEL_ID = "astra_mesh_service"
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: TorXOneService? = null

        fun getInstance(): TorXOneService? = instance

        fun start(context: Context) {
            val intent = Intent(context, TorXOneService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TorXOneService::class.java)
            context.stopService(intent)
        }
    }

    // Public accessors for UI to observe state
    lateinit var db: AppDatabase
        private set
    lateinit var nearbyManager: NearbyConnectionManager
        private set
    lateinit var torManager: TorManager
        private set
    lateinit var messageRouter: MessageRouter
        private set
    lateinit var identityManager: IdentityManager
        private set
    lateinit var mediaTransferManager: com.torxone.app.transfer.MediaTransferManager
        private set
    lateinit var realtimeEngineManager: com.torxone.app.realtime.RealtimeEngineManager
        private set
    lateinit var astraFastLane: com.torxone.app.realtime.AstraFastLane
        private set
    lateinit var callManager: com.torxone.app.call.CallManager
        private set
    lateinit var presenceManager: com.torxone.app.presence.PresenceManager
        private set
    lateinit var profileSyncManager: com.torxone.app.identity.profile.ProfileSyncManager
        private set
    lateinit var musicNoteManager: com.torxone.app.music.MusicNoteManager
        private set
    lateinit var listenTogetherManager: com.torxone.app.music.ListenTogetherManager
        private set
    lateinit var settingsManager: com.torxone.app.data.SettingsManager
        private set

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()
    private val isConfigured = kotlinx.coroutines.flow.MutableStateFlow(false)

    inner class LocalBinder : Binder() {
        fun getService(): TorXOneService = this@TorXOneService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "[INIT] Service onCreate")

        NotificationHelper.createChannels(this)
        registerAutoRecoveryReceivers()

        // Initialize core components
        identityManager = IdentityManager(this)

        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "astra-mesh-db"
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12
            )
            .build()

        nearbyManager = NearbyConnectionManager(this)
        torManager = TorManager(this)
        messageRouter = MessageRouter(serviceScope, db, nearbyManager, torManager)
        realtimeEngineManager = com.torxone.app.realtime.RealtimeEngineManager(this, messageRouter)
        astraFastLane = com.torxone.app.realtime.AstraFastLane(realtimeEngineManager)
        mediaTransferManager = com.torxone.app.transfer.MediaTransferManager(this, db, messageRouter, astraFastLane)
        callManager = com.torxone.app.call.CallManager(this, db, messageRouter)
        presenceManager = com.torxone.app.presence.PresenceManager(serviceScope, messageRouter)
        
        val profileCache = com.torxone.app.identity.profile.ProfileCacheManagerImpl(this)
        val imageProcessor = com.torxone.app.media.ImageProcessor(this)
        val profileRepository = com.torxone.app.identity.profile.ProfileRepositoryImpl(db.profileDao(), identityManager, profileCache, imageProcessor)
        profileSyncManager = com.torxone.app.identity.profile.ProfileSyncManager(this, profileRepository, messageRouter)
        val musicRepository = com.torxone.app.music.MusicNoteRepositoryImpl(db.musicNoteDao())
        musicNoteManager = com.torxone.app.music.MusicNoteManager(this, serviceScope, db, identityManager, musicRepository, messageRouter)
        listenTogetherManager = com.torxone.app.music.ListenTogetherManager(serviceScope, messageRouter)
        
        settingsManager = com.torxone.app.data.SettingsManager(this)

        wireNetworking()
        startUpdateChecker()
        Log.d(TAG, "[INIT] All networking components initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("TorX One is running", "Secure mesh network active")
        startForeground(NOTIFICATION_ID, notification)

        // Start networking if identity exists
        configureAndStart()

        return START_STICKY
    }

    fun configureAndStart() {
        val identity = identityManager.loadIdentity() ?: run {
            Log.w(TAG, "[START] No identity found, waiting for setup")
            return
        }

        nearbyManager.setLocalName(identity.name)
        messageRouter.identity = identity
        messageRouter.mySigningKeyHex = CryptoManager.toHex(identity.signingPublicKey)
        messageRouter.myOnionAddress = identityManager.loadOnionAddress() ?: ""

        isConfigured.value = true

        // Start retry loop for any pending messages from previous session
        messageRouter.ensureRetryLoopRunning()

        Log.d(TAG, "[START] Networking started for ${identity.name}")
        updateNotification("Connected as ${identity.name}", "Mesh + Tor active")
    }

    /**
     * Safely stops and restarts all networking components.
     * Used primarily when restoring a new identity from a backup without requiring an app restart.
     */
    fun restartNetworking() {
        Log.d(TAG, "[RESTART] Restarting all networking services...")
        serviceScope.launch {
            // Stop existing Tor
            torManager.stop()
            
            // Stop Nearby
            nearbyManager.stopAll()
            
            // Give services a moment to cleanly shutdown
            delay(1000)
            
            // Re-configure and start
            withContext(Dispatchers.Main) {
                configureAndStart()
            }
        }
    }

    private fun wireNetworking() {
        nearbyManager.onMessageReceived = { endpointId, raw ->
            messageRouter.handleNearbyPayload(endpointId, raw)
        }

        nearbyManager.onConnectionEstablished = { endpointId, name ->
            serviceScope.launch {
                val existing = db.contactDao().getContactByEndpoint(endpointId)
                if (existing != null) {
                    db.contactDao().updateConnectionStatus(endpointId, true)
                }
            }
            messageRouter.broadcastHello(endpointId)
            Log.d(TAG, "[NEARBY] Connection established with $name ($endpointId)")
        }

        nearbyManager.onDisconnected = { endpointId ->
            serviceScope.launch {
                db.contactDao().clearEndpoint(endpointId)
            }
            Log.d(TAG, "[NEARBY] Disconnected from $endpointId")
        }

        torManager.onTorMessageReceived = { raw ->
            Log.d(TAG, "[TOR] Received message on hidden service")
            messageRouter.handleTorPayload(raw)
        }

        serviceScope.launch {
            torManager.onionAddress.collectLatest { onion ->
                if (onion.isNotBlank()) {
                    messageRouter.myOnionAddress = onion
                    identityManager.saveOnionAddress(onion)
                    Log.d(TAG, "[TOR] Onion address saved: ${onion.take(20)}...")
                    updateNotification("Connected", "Tor: ${onion.take(16)}...")
                }
            }
        }

        serviceScope.launch {
            torManager.torState.collectLatest { state ->
                when (state) {
                    is TorState.Connected -> {
                        updateNotification("Connected", "Tor: ${state.onionAddress.take(16)}...")
                        messageRouter.retryPendingNow()
                    }
                    is TorState.Reconnecting -> {
                        updateNotification("Reconnecting via Tor...", "Messages are queued safely")
                    }
                    is TorState.Failed -> {
                        updateNotification("Tor offline", "Messages will retry when Tor reconnects")
                    }
                    is TorState.Starting -> {
                        updateNotification("Starting Tor", state.message)
                    }
                    TorState.Idle,
                    TorState.Stopped -> {
                        updateNotification("TorX One is running", "Secure mesh network standby")
                    }
                }
            }
        }

        serviceScope.launch {
            kotlinx.coroutines.flow.combine(isConfigured, settingsManager.torEnabledFlow) { configured, enabled ->
                Pair(configured, enabled)
            }.collectLatest { (configured, enabled) ->
                if (configured) {
                    if (enabled) torManager.start() else torManager.stop()
                }
            }
        }

        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                isConfigured,
                settingsManager.hideOnlineStatusFlow,
                settingsManager.bluetoothScanningFlow,
                settingsManager.wifiDirectScanningFlow,
                settingsManager.performanceModeFlow
            ) { configured, hidden, bluetoothScanning, wifiDirectScanning, performanceMode ->
                NearbyPowerPolicy(configured, hidden, bluetoothScanning, wifiDirectScanning, performanceMode)
            }.collectLatest { policy ->
                if (policy.configured) {
                    applyNearbyPowerPolicy(policy)
                }
            }
        }

        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsManager.backgroundSyncFrequencyFlow,
                settingsManager.performanceModeFlow
            ) { frequency, performanceMode ->
                backgroundRetryIntervalMs(frequency, performanceMode)
            }.collectLatest { interval ->
                messageRouter.setBackgroundRetryInterval(interval)
            }
        }
    }

    private fun applyNearbyPowerPolicy(policy: NearbyPowerPolicy) {
        if (policy.hideOnlineStatus || (!policy.bluetoothScanning && !policy.wifiDirectScanning)) {
            nearbyManager.stopAll()
            return
        }

        nearbyManager.startAdvertising()
        if (policy.performanceMode == "battery_saver") {
            nearbyManager.stopDiscovery()
        } else {
            nearbyManager.startDiscovery()
        }
    }

    private data class NearbyPowerPolicy(
        val configured: Boolean,
        val hideOnlineStatus: Boolean,
        val bluetoothScanning: Boolean,
        val wifiDirectScanning: Boolean,
        val performanceMode: String
    )

    private fun backgroundRetryIntervalMs(frequency: String, performanceMode: String): Long {
        return when (frequency) {
            "low" -> 60_000L
            "fast" -> 10_000L
            else -> if (performanceMode == "battery_saver") 60_000L else 30_000L
        }
    }

    fun updateNotification(title: String, text: String) {
        val notification = NotificationHelper.buildForegroundServiceNotification(this, title, text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(title: String, text: String): Notification {
        return NotificationHelper.buildForegroundServiceNotification(this, title, text)
    }

    private fun startUpdateChecker() {
        val updater = GitHubUpdater(this)
        serviceScope.launch {
            while (isActive) {
                try {
                    val info = updater.checkForUpdates(manual = false)
                    if (info != null && info.isUpdateAvailable) {
                        NotificationHelper.showUpdateNotification(this@TorXOneService, info.version)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[UPDATER] Error checking for updates in background", e)
                }
                delay(60 * 60 * 1000L) // Check every hour (GitHubUpdater rate limits it internally to 24h)
            }
        }
    }

    private val autoRecoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        Log.d(TAG, "[RECOVERY] Bluetooth turned on, restarting Nearby Connections")
                        if (identityManager.loadIdentity() != null) {
                            nearbyManager.startAdvertising()
                            nearbyManager.startDiscovery()
                        }
                    }
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    if (state == WifiManager.WIFI_STATE_ENABLED) {
                        Log.d(TAG, "[RECOVERY] Wi-Fi turned on, restarting Nearby Connections")
                        if (identityManager.loadIdentity() != null) {
                            nearbyManager.startAdvertising()
                            nearbyManager.startDiscovery()
                        }
                    }
                }
            }
        }
    }

    private fun registerAutoRecoveryReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        registerReceiver(autoRecoveryReceiver, filter)
    }

    // Removed createNotification duplicate method as it's replaced by NotificationHelper usage

    override fun onDestroy() {
        Log.d(TAG, "[STOP] Service onDestroy")
        instance = null
        try {
            unregisterReceiver(autoRecoveryReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        nearbyManager.stopAll()
        torManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }
}
