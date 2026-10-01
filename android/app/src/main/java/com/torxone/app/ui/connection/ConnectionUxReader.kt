package com.torxone.app.ui.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.torxone.app.data.TorXDatabase
import com.torxone.app.transport.AddressableTransport
import com.torxone.app.transport.TransportAvailability
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportRouter
import com.torxone.app.transport.TransportType
import com.torxone.app.transport.nearby.NearbyTransport
import com.torxone.app.transport.nearby.RouteState
import com.torxone.app.transport.tor.TorConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** Read-only connection diagnostics. It never selects a route or sends/re-encrypts a message. */
class ConnectionUxReader(
    private val db: TorXDatabase,
    private val router: TransportRouter,
    private val torState: StateFlow<TorConnectionState>,
    private val nearby: NearbyTransport,
    context: Context? = null,
    private val isSendingPaused: suspend (String) -> Boolean = { false }
) {
    private val connectivity = context?.applicationContext?.getSystemService(ConnectivityManager::class.java)

    fun observe(conversationId: String): Flow<ConnectionUxSnapshot> = flow {
        while (currentCoroutineContext().isActive) {
            try { emit(read(conversationId)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                emit(ConnectionUxSnapshot(headline = "Connection information unavailable",
                    error = "Could not read connection state. Messages remain in the normal outbox."))
            }
            delay(2_000)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun read(conversationId: String): ConnectionUxSnapshot {
        val contact = db.contactDao().getByConversationId(conversationId)
        val relationshipId = contact?.relationshipId
        val connection = relationshipId?.let { db.connectionDao().getByRelationshipId(it) }
        val session = relationshipId?.let { db.sessionDao().getByRelationshipId(it) }
        val destination = connection?.takeIf { it.state == "ACTIVE" }
            ?.let { TransportDestination(it.sendQueueId, relationshipId = it.relationshipId) }
        val paths = listOf(
            TransportType.TOR to "Tor", TransportType.NEARBY to "Nearby",
            TransportType.WIFI_DIRECT to "Wi-Fi Direct", TransportType.LORA to "Radio",
            TransportType.WIFI_HALOW to "Wi-Fi HaLow", TransportType.GATEWAY to "Gateway",
            TransportType.RELAY to "Mesh relay"
        ).map { (type, name) ->
            val transport = router.getTransport(type)
            val availability = transport?.let { withTimeoutOrNull(500) { it.availability().first() } }
            val hasRoute = destination != null && transport is AddressableTransport && transport.canRoute(destination)
            val ready = availability is TransportAvailability.Available && hasRoute
            val status = when {
                type == TransportType.TOR && torState.value !is TorConnectionState.Ready -> torLabel(torState.value)
                transport == null -> "Unavailable: transport not registered"
                availability == null -> "Availability not reported"
                availability is TransportAvailability.Unavailable -> "Unavailable: ${availability.reason.take(180)}"
                destination == null -> "Ready locally; no active direct-peer connection"
                ready && type == TransportType.TOR -> "Ready locally; peer route configured"
                ready -> "Ready for this peer"
                else -> "Ready locally; no route to this peer"
            }
            ConnectionPathInfo(name, status, ready)
        }
        val capabilities = runCatching { connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) } }.getOrNull()
        val internetValidated = if (connectivity == null) null else capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
        val bearer = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            else -> "Network"
        }
        val internet = when (internetValidated) {
            true -> "$bearer · validated Internet"
            false -> "$bearer · no validated Internet"
            null -> "Internet state unavailable"
        }
        val searching = relationshipId?.let { nearby.directRouteTable.getRouteByRelationship(it)?.state }
            .let { it == RouteState.CONNECTING || it == RouteState.AUTHENTICATING } ||
            withTimeoutOrNull(500) { nearby.healthState.first() } == com.torxone.app.transport.nearby.TransportHealthState.DISCOVERING
        val pendingMessages = db.featureDao().pendingMessageCount(conversationId)
        val pendingControls = db.featureDao().pendingControlCount(conversationId)
        return ConnectionUxPresentation.create(internet, internetValidated, paths,
            contact?.let { it.verificationState == "VERIFIED" },
            relationshipId?.let { session?.state == "ACTIVE" },
            pendingMessages, searching, isSendingPaused(conversationId),
            pendingControls)
    }

    private fun torLabel(state: TorConnectionState): String = when (state) {
        TorConnectionState.Stopped -> "Stopped"
        TorConnectionState.Starting -> "Starting"
        is TorConnectionState.Bootstrapping -> "Connecting: ${state.percent}%"
        TorConnectionState.OnionPublishing -> "Publishing address"
        is TorConnectionState.Ready -> "Ready locally"
        is TorConnectionState.Failed -> "Unavailable: ${state.reason.take(180)}"
    }
}
