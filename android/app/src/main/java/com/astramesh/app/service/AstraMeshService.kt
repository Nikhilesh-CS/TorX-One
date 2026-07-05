package com.astramesh.app.service

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
import com.astramesh.app.R
import com.astramesh.app.crypto.CryptoManager
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.identity.IdentityManager
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.NearbyConnectionManager
import com.astramesh.app.network.TorManager
import com.astramesh.app.updater.GitHubUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Central service that owns all networking: Nearby, Tor, and MessageRouter.
 * Runs as a foreground service so connections survive Activity lifecycle changes.
 */
class AstraMeshService : Service() {
    companion object {
        private const val TAG = "AstraMeshService"
        private const val CHANNEL_ID = "astra_mesh_service"
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: AstraMeshService? = null

        fun getInstance(): AstraMeshService? = instance

        fun start(context: Context) {
            val intent = Intent(context, AstraMeshService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AstraMeshService::class.java)
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
    lateinit var mediaTransferManager: com.astramesh.app.transfer.MediaTransferManager
        private set
    lateinit var settingsManager: com.astramesh.app.data.SettingsManager
        private set

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): AstraMeshService = this@AstraMeshService
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
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .build()

        nearbyManager = NearbyConnectionManager(this)
        torManager = TorManager(this)
        messageRouter = MessageRouter(serviceScope, db, nearbyManager, torManager)
        mediaTransferManager = com.astramesh.app.transfer.MediaTransferManager(this, db, messageRouter)
        settingsManager = com.astramesh.app.data.SettingsManager(this)

        wireNetworking()
        startUpdateChecker()
        Log.d(TAG, "[INIT] All networking components initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Astra Mesh is running", "Secure mesh network active")
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

        nearbyManager.startAdvertising()
        nearbyManager.startDiscovery()
        torManager.start()

        // Start retry loop for any pending messages from previous session
        messageRouter.ensureRetryLoopRunning()

        Log.d(TAG, "[START] Networking started for ${identity.name}")
        updateNotification("Connected as ${identity.name}", "Mesh + Tor active")
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
                db.contactDao().updateConnectionStatus(endpointId, false)
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
                        NotificationHelper.showUpdateNotification(this@AstraMeshService, info.version)
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
