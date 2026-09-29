package com.torxone.app.transport.tor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TorHealthMonitor(controller: TorController) {
    val healthy: Flow<Boolean> = controller.state.map { it is TorConnectionState.Ready }
}
