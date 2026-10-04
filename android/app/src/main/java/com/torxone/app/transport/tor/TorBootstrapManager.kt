package com.torxone.app.transport.tor

class TorBootstrapManager(private val controller: TorController) {
    fun start() = controller.start()
    fun retry(): Boolean = controller.retry()
    suspend fun awaitReady(timeoutMs: Long = 120_000): Boolean = controller.awaitReady(timeoutMs)
    fun stop() = controller.stop()
}
