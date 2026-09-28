package it.uhde.periph.chips.comms

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.OutputPin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

private const val REG_VERSION = 0x42
private const val EXPECTED_VERSION = 0x12

private class FakeOutputPin : OutputPin {
    val calls = mutableListOf<Boolean>()
    override fun set(high: Boolean) { calls.add(high) }
    override fun close() {}
}

private class FakeInputPin : InputPin {
    val handlers = CopyOnWriteArrayList<EdgeHandler>()
    override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) { handlers.add(handler) }
    override fun offEdge(handler: EdgeHandler) { handlers.remove(handler) }
    override fun close() {}
    fun fire() { handlers.forEach { it.onEdge() } }
}

private fun newConnection(): MockConnection {
    val c = MockConnection()
    c.setRegister(REG_VERSION, EXPECTED_VERSION)
    return c
}

private fun hasWrite(connection: MockConnection, vararg bytes: Int): Boolean {
    val want = ByteArray(bytes.size) { bytes[it].toByte() }
    return connection.writes().any { it.contentEquals(want) }
}

class Rfm9xTest {

    @Test
    fun initAndSend() {
        val connection = newConnection()
        val sensor = Rfm95Minimal(connection, 915_000_000L)
        assertTrue(hasWrite(connection, 0x86, 0xE4))
        assertTrue(hasWrite(connection, 0x87, 0xC0))
        assertTrue(hasWrite(connection, 0x88, 0x00))
        assertTrue(hasWrite(connection, 0x9D, 0x72))
        assertTrue(hasWrite(connection, 0x9E, 0x77))
        assertTrue(hasWrite(connection, 0x89, 0x8F))

        val badVersionConn = MockConnection()
        badVersionConn.setRegister(REG_VERSION, 0x99)
        assertThrows(IOException::class.java) { Rfm95Minimal(badVersionConn, 915_000_000L) }

        assertThrows(IllegalArgumentException::class.java) { Rfm95Minimal(connection, 433_000_000L) }

        val lfConnection = newConnection()
        Rfm96Minimal(lfConnection, 433_000_000L)

        connection.setRegister(0x12, 0x08)
        val base = connection.writes().size
        sensor.send(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte()))
        val w = connection.writes()
        assertArrayEquals(byteArrayOf(0x8D.toByte(), 0x80.toByte()), w[base + 1])
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte()), w[base + 2])
        assertArrayEquals(byteArrayOf(0xA2.toByte(), 0x03), w[base + 3])
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x40), w[base + 4])
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x83.toByte()), w[base + 5])
        assertTrue(hasWrite(connection, 0x92, 0x08))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x81.toByte()), w[w.size - 1])

        // NOTE: send() silently truncates an over-255-byte payload instead of
        // rejecting it -- same no-exceptions-style clamp pattern as configure().
        connection.setRegister(0x12, 0x08)
        sensor.send(ByteArray(256))
        assertTrue(hasWrite(connection, 0xA2, 255))
    }

    @Test
    fun receive() {
        val connection = newConnection()
        val sensor = Rfm95Minimal(connection, 915_000_000L)

        connection.setRegister(0x12, 0x40)
        connection.setRegister(0x10, 0x00)
        connection.setRegister(0x13, 0x03)
        connection.setRegister(0x00, 0xAA, 0xBB, 0xCC)
        val base = connection.writes().size
        val payload = sensor.receive(100)
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x86.toByte()), connection.writes()[base + 2])
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), payload)
        assertTrue(hasWrite(connection, 0x92, 0x40))

        connection.setRegister(0x12, 0x00)
        assertNull(sensor.receive(10))
    }

    @Test
    fun configureSetFrequencySetTxPower() {
        val connection = newConnection()
        val full = Rfm95Full(connection, 915_000_000L)

        full.configure(9, 125.0f, 5, true)
        assertTrue(hasWrite(connection, 0xB1, 0x03))
        assertTrue(hasWrite(connection, 0x9D, 0x72))
        assertTrue(hasWrite(connection, 0x9E, 0x97))

        val rfm97Connection = newConnection()
        val rfm97 = Rfm97Full(rfm97Connection, 915_000_000L)
        rfm97.configure(12, 125.0f, 5, true)
        assertTrue(hasWrite(rfm97Connection, 0x9E, 0x97))

        full.setFrequency(868_000_000L)
        assertTrue(hasWrite(connection, 0x86, 0xD9))
        assertTrue(hasWrite(connection, 0x87, 0x00))

        full.setTxPower(20, true)
        assertTrue(hasWrite(connection, 0xCD, 0x87))
        assertTrue(hasWrite(connection, 0x8B, 0x3B))
        assertTrue(hasWrite(connection, 0x89, 0x8F))

        full.setTxPower(10, false)
        assertTrue(hasWrite(connection, 0x89, 0x7A))
    }

    @Test
    fun telemetryAndPowerControl() {
        val connection = newConnection()
        val full = Rfm95Full(connection, 915_000_000L)

        full.standby()
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x81.toByte()), connection.writes().last())
        full.sleep()
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x80.toByte()), connection.writes().last())

        assertEquals(0x12, full.version())
        connection.setRegister(0x1B, 100)
        assertEquals(-137f + 100f, full.rssi())
        connection.setRegister(0x1A, 90)
        assertEquals(-137f + 90f, full.lastPacketRssi())
        connection.setRegister(0x19, 20)
        assertTrue(Math.abs(full.lastPacketSnr() - 5.0f) < 1e-6f)
        connection.setRegister(0x19, 0xF4)
        assertTrue(Math.abs(full.lastPacketSnr() - (-3.0f)) < 1e-6f)
    }

    @Test
    fun receiveContinuousAndReadPacket() {
        val connection = newConnection()
        val full = Rfm95Full(connection, 915_000_000L)

        full.receiveContinuous()
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x85.toByte()), connection.writes().last())

        connection.setRegister(0x12, 0x40)
        connection.setRegister(0x10, 0x00)
        connection.setRegister(0x13, 0x02)
        connection.setRegister(0x00, 0x11, 0x22)
        assertArrayEquals(byteArrayOf(0x11, 0x22), full.readPacket())

        connection.setRegister(0x12, 0x00)
        assertNull(full.readPacket())

        full.stopReceive()
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x81.toByte()), connection.writes().last())
    }

    @Test
    fun receiveInterrupt() {
        val connection = newConnection()
        val full = Rfm95Full(connection, 915_000_000L)
        assertThrows(IllegalStateException::class.java) { full.receive(2000, true) }

        val dio0Connection = newConnection()
        dio0Connection.setRegister(0x12, 0x40)
        dio0Connection.setRegister(0x10, 0x00)
        dio0Connection.setRegister(0x13, 0x01)
        dio0Connection.setRegister(0x00, 0x99)
        val dio0 = FakeInputPin()
        val fullWithDio0 = Rfm95Full(dio0Connection, 915_000_000L, null, dio0)

        val firer = Thread {
            Thread.sleep(5)
            dio0.fire()
        }
        firer.start()
        val payload = fullWithDio0.receive(2000, true)
        firer.join()
        assertArrayEquals(byteArrayOf(0x99.toByte()), payload)
    }

    @Test
    fun reset() {
        val connection = newConnection()
        val full = Rfm95Full(connection, 915_000_000L)
        full.reset()  // no resetPin -> falls back to a POR wait, not an error

        val resetPin = FakeOutputPin()
        val connection2 = newConnection()
        val withReset = Rfm95Full(connection2, 915_000_000L, resetPin, null)
        assertEquals(listOf(false, true), resetPin.calls)
        resetPin.calls.clear()
        withReset.reset()
        assertEquals(listOf(false, true), resetPin.calls)
    }
}
