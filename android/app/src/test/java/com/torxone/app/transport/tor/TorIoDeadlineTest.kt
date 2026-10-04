package com.torxone.app.transport.tor

import java.io.DataOutputStream
import java.io.IOException
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TorIoDeadlineTest {
    @Test fun trickledBytesCannotExtendAnAbsoluteFrameDeadline() = runBlocking {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = Socket("127.0.0.1", listener.localPort)
        val receiver = listener.accept()
        receiver.soTimeout = 200
        DataOutputStream(client.getOutputStream()).apply { writeInt(64 * 1_024); flush() }
        val read = async(Dispatchers.IO) {
            try {
                TorIncomingFrameReader(receiver, PushbackInputStream(receiver.getInputStream(), 4), 400).readFrame()
                fail("An incomplete trickled frame must hit its absolute deadline")
            } catch (_: IOException) { assertTrue(receiver.isClosed) }
        }
        val trickler = launch(Dispatchers.IO) {
            try {
                repeat(100) { client.getOutputStream().write(1); client.getOutputStream().flush(); delay(20) }
            } catch (_: IOException) { }
        }
        try {
            withTimeout(2_000) { read.await() }
            assertTrue(receiver.isClosed)
        } finally { trickler.cancelAndJoin(); receiver.close(); client.close(); listener.close() }
    }

    @Test fun completedReadCancelsItsDeadlineBeforeReusingConnection() = runBlocking {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = Socket("127.0.0.1", listener.localPort)
        val receiver = listener.accept()
        try {
            receiver.soTimeout = 1_000
            val frames = TorIncomingFrameReader(receiver, PushbackInputStream(receiver.getInputStream(), 4), 100)
            val output = DataOutputStream(client.getOutputStream())
            TorStreamFraming.writeFrame(output, byteArrayOf(1))
            assertArrayEquals(byteArrayOf(1), frames.readFrame())
            delay(200)
            assertFalse(receiver.isClosed)
            TorStreamFraming.writeFrame(output, byteArrayOf(2))
            assertArrayEquals(byteArrayOf(2), frames.readFrame())
        } finally { receiver.close(); client.close(); listener.close() }
    }

    @Test fun headerWithoutFirstFrameCannotOccupyAnIdleSlot() = runBlocking {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = Socket("127.0.0.1", listener.localPort)
        val receiver = listener.accept()
        receiver.soTimeout = 5_000
        try {
            val read = async(Dispatchers.IO) {
                try {
                    TorIncomingFrameReader(receiver, PushbackInputStream(receiver.getInputStream(), 4), 100).readFrame()
                    fail("First-frame admission must finish before the stream idle timeout")
                } catch (_: IOException) { assertTrue(receiver.isClosed) }
            }
            withTimeout(1_000) { read.await() }
        } finally { receiver.close(); client.close(); listener.close() }
    }
}
