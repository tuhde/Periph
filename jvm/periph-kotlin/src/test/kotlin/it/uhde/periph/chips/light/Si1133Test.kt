package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.function.IntConsumer

class Si1133Test {

    /** Simulates the command engine: RESPONSE0 counter, parameter table, FORCE packing HOSTOUT. */
    private class Mock : MockConnection() {
        var partId = 0x33
        val params = IntArray(64)
        var counter = 0x0F
        var error = 0
        var resp1 = 0
        var irqEnable = 0
        var irqStatus = 0
        var hostout = IntArray(26)
        val samples = intArrayOf(0x012345, -5, 0xABCD, 0, 0, 0)
        val commands = mutableListOf<Int>()
        var ignoreCommands = false
        var pin: InputPin? = null

        fun resp0() = if (error != 0) 0x30 or (error and 0x0F) else 0x20 or counter

        fun command(cmd: Int, hostin: Int) {
            commands += cmd
            if (ignoreCommands) return
            when {
                cmd == 0x00 -> { counter = 0; error = 0; return }
                cmd == 0x01 -> { params.fill(0); counter = 0x0F; error = 0; irqEnable = 0; return }
                cmd and 0xC0 == 0x80 -> {
                    val p = cmd and 0x3F
                    if (p > 0x2B) { error = 0x11; return }
                    params[p] = hostin
                    resp1 = hostin
                }
                cmd and 0xC0 == 0x40 -> resp1 = params[cmd and 0x3F]
                cmd == 0x11 -> {
                    hostout = IntArray(26)
                    var pos = 0
                    for (ch in 0 until 6) {
                        if (params[1] and (1 shl ch) == 0) continue
                        val v = samples[ch]
                        if (params[0x04 + 4 * ch] and 0x40 != 0) {
                            hostout[pos++] = (v shr 16) and 0xFF
                            hostout[pos++] = (v shr 8) and 0xFF
                            hostout[pos++] = v and 0xFF
                        } else {
                            hostout[pos++] = (v shr 8) and 0xFF
                            hostout[pos++] = v and 0xFF
                        }
                    }
                    irqStatus = irqStatus or (params[1] and irqEnable)
                }
                cmd == 0x12 || cmd == 0x13 -> {}
                else -> { error = 0x10; return }
            }
            counter = (counter + 1) and 0x0F
        }

        override fun write(data: ByteArray) {
            super.write(data)
            val reg = data[0].toInt() and 0xFF
            when {
                reg == 0x0A && data.size >= 3 -> command(data[2].toInt() and 0xFF, data[1].toInt() and 0xFF)
                reg == 0x0B && data.size >= 2 -> command(data[1].toInt() and 0xFF, 0)
                reg == 0x0F && data.size >= 2 -> irqEnable = data[1].toInt() and 0xFF
            }
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            super.writeRead(data, n)
            val reg = data[0].toInt() and 0xFF
            val block = when {
                reg == 0x00 -> intArrayOf(partId, 0x03, 0x10)
                reg == 0x10 -> intArrayOf(resp1)
                reg == 0x11 -> intArrayOf(resp0())
                reg == 0x12 -> intArrayOf(irqStatus).also { irqStatus = 0 }
                reg >= 0x13 -> hostout.copyOfRange(reg - 0x13, 26)
                else -> IntArray(0)
            }
            return ByteArray(n) { if (it < block.size) block[it].toByte() else 0 }
        }

        override fun intPin(): InputPin? = pin
    }

    private class FakePin : InputPin {
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        override fun onEdge(h: EdgeHandler, t: EdgeTrigger) { handler = h; trigger = t }
        override fun offEdge(h: EdgeHandler) { if (h == handler) handler = null }
        override fun isHigh() = true
        override fun close() {}
    }

    private lateinit var c: Mock

    private fun wrote(vararg frame: Int) = c.writes().any { f ->
        f.size == frame.size && f.indices.all { (f[it].toInt() and 0xFF) == frame[it] }
    }

    private fun make(): Si1133Full {
        c = Mock()
        val s = Si1133Full(c)
        assertTrue(c.writes().isEmpty(), "constructor touched the bus")
        s.init()
        return s
    }

    @Test
    fun initConfiguresChannelsAndReads() {
        val s = make()
        assertEquals(0x01, c.commands[0])
        assertEquals(listOf(0x18, 0x09, 0x40), c.params.slice(0x02..0x04))
        assertEquals(listOf(0x0B, 0x07, 0x40), c.params.slice(0x06..0x08))
        assertEquals(0x03, c.params[0x01])
        assertEquals(0x3F, c.irqEnable)
        assertTrue(wrote(0x0A, 0x03, 0x81))
        assertEquals(Si1133Minimal.Reading(0x012345, -5), s.read())
        assertEquals(0x11, c.commands.last())
    }

    @Test
    fun wrongPartId() {
        c = Mock()
        c.partId = 0x32
        assertThrows(IOException::class.java) { Si1133Minimal(c).init() }
    }

    @Test
    fun channelsAndScratchReads() {
        val s = make()
        s.configureChannel(2, Si1133Minimal.MUX_LARGE_IR, 3, 2, 1, true, false, 1)
        assertEquals(listOf(0x22, 0xA3, 0x08), c.params.slice(0x0A..0x0C))
        s.enableChannels(0x07)
        assertArrayEquals(intArrayOf(0x012345, -5, 0xABCD), s.readChannels())
        assertArrayEquals(intArrayOf(0x012345, -5, 0xABCD), s.readChannels(false))
        assertEquals(Si1133Minimal.Reading(0x012345, -5), s.read())
        assertThrows(IllegalArgumentException::class.java) { s.configureChannel(6, 0) }
        assertThrows(IllegalArgumentException::class.java) { s.configureChannel(0, 0x18, hwGain = 12) }
        assertThrows(IllegalArgumentException::class.java) { s.enableChannels(0x40) }
        c.samples[5] = 1234
        assertEquals(1234, s.readUv())
        assertEquals(listOf(0x18, 0x09), c.params.slice(0x16..0x17))
        assertEquals(0x07, c.params[0x01])
        assertEquals(1234, s.readWhite())
        assertEquals(0x0B, c.params[0x16])
        assertEquals(1234, s.readIr(Si1133Minimal.MUX_MEDIUM_IR))
        assertEquals(0x01, c.params[0x16])
        assertEquals(1234, s.readIr())
        assertEquals(0x00, c.params[0x16])
        assertThrows(IllegalArgumentException::class.java) { s.readIr(0x0B) }
        s.force()
        assertEquals(0x11, c.commands.last())
    }

    @Test
    fun autonomousBurstAndThresholds() {
        val s = make()
        s.startAutonomous(1.0, intArrayOf(2, 3, 4))
        assertEquals(1250, (c.params[0x1A] shl 8) or c.params[0x1B])
        assertEquals(listOf(2, 3, 4), c.params.slice(0x1C..0x1E))
        assertEquals(0x40, c.params[0x05])
        assertEquals(0x40, c.params[0x09])
        assertEquals(0x13, c.commands.last())
        s.setChannelCounter(2, 3)
        assertEquals(0xC0, c.params[0x0D])
        s.pause()
        assertEquals(0x12, c.commands.last())
        assertThrows(IllegalArgumentException::class.java) { s.startAutonomous(0.0) }
        s.setBurst(4)
        assertEquals(0x84, c.params[0x2B])
        s.setBurst(0)
        assertEquals(0, c.params[0x2B])
        assertThrows(IllegalArgumentException::class.java) { s.setBurst(128) }
        s.setThreshold(1, 0x1234)
        assertEquals(listOf(0x12, 0x34), c.params.slice(0x27..0x28))
        s.setChannelThreshold(0, 2)
        assertEquals(0x42, c.params[0x04])
        assertThrows(IllegalArgumentException::class.java) { s.setThreshold(3, 0) }
    }

    @Test
    fun parametersStatusAndReset() {
        val s = make()
        s.writeParameter(0x1C, 0x55)
        assertEquals(0x55, c.params[0x1C])
        assertEquals(0x55, s.readParameter(0x1C))
        assertEquals(0x5C, c.commands.last())
        assertEquals(Si1133Full.Response(0x20, false, c.counter), s.readResponse())
        assertThrows(IOException::class.java) { s.writeParameter(0x30, 1) }
        assertEquals(0x00, c.commands.last())
        assertEquals(0, c.error)
        c.irqStatus = 0x05
        assertEquals(0x05, s.dataReady())
        assertEquals(0, s.dataReady())
        assertEquals(Si1133Full.Id(0x33, 0x03, 0x10), s.readId())
        s.setI2cAddress(0x30)
        assertEquals(0x30, c.params[0x00])
        assertThrows(IllegalArgumentException::class.java) { s.setI2cAddress(0x80) }
        s.reset()
        assertEquals(0x03, c.params[0x01])
        assertEquals(0x18, c.params[0x02])
        assertEquals(Si1133Minimal.Reading(0x012345, -5), s.read())
    }

    @Test
    fun interrupt() {
        val s = make()
        s.disableInterrupt(Si1133Full.SOURCE_CHANNEL1)
        assertEquals(0x3D, c.irqEnable)
        s.enableInterrupt(Si1133Full.SOURCE_CHANNEL1)
        assertEquals(0x3F, c.irqEnable)
        c.irqStatus = 0x02
        assertEquals(0x02, s.pollInterrupt())
        val pin = FakePin()
        val seen = mutableListOf<Int>()
        s.onInterrupt(IntConsumer { seen += it }, pin)
        assertNotNull(pin.handler)
        assertEquals(EdgeTrigger.FALLING, pin.trigger)
        c.irqStatus = 0x01
        pin.handler!!.onEdge()
        assertEquals(listOf(0x01), seen)
        s.offInterrupt()
        assertNull(pin.handler)
    }

    @Test
    fun commandTimeout() {
        c = Mock()
        val s = Si1133Minimal(c)
        s.init()
        c.ignoreCommands = true
        assertThrows(IOException::class.java) { s.read() }
    }
}
