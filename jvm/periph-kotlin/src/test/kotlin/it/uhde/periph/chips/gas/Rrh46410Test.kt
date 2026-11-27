package it.uhde.periph.chips.gas

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Collections

class Rrh46410Test {

    companion object {
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        fun cs(d: ByteArray, len: Int): Int {
            var s = 0
            for (i in 0 until len) s += d[i].toInt() and 0xFF
            return s.inv() and 0xFF
        }
    }

    /** Simulates the RRH46410 request/response protocol with checksums. */
    private class Sim : MockConnection() {
        val written = mutableListOf<ByteArray>()
        var response: ByteArray? = null
        var mode = 0; var counter = 0; var gpio = 0x0C; var gpioDir = 0; var humidity = -1
        var pid = 0x2310; var failWrites = 0; var statusOverride = -1
        var hasResult = false; var badChecksum = false; var requestsOk = true
        var pin: InputPin? = null

        @Synchronized fun sample(n: Int) { hasResult = true; counter = (counter + n) and 0xFF }

        @Synchronized
        override fun write(data: ByteArray) {
            written.add(data.clone())
            if (failWrites > 0) { failWrites--; throw IOException("nack") }
            if (cs(data, data.size - 1) != (data.last().toInt() and 0xFF)) requestsOk = false
            val cmd = data[0].toInt() and 0xFF
            val p = data.copyOfRange(1, data.size - 1)
            var status = 0
            var out = ByteArray(0)
            when (cmd) {
                0x00 -> out = b(pid shr 8, pid and 0xFF, 1, 2, 3)
                0x01 -> out = b(1, 2, 3, 4, 5, 6)
                0x10 -> out = b(mode)
                0x11 -> { mode = p[0].toInt() and 0xFF; counter = 0; hasResult = false }
                0x12 -> humidity = p[0].toInt() and 0xFF
                0x18 -> {
                    if (!hasResult) status = 0x10
                    out = if (mode == 0x05) b(counter, 0x01, 0xF4, 0x0B, 0xB8)
                    else b(counter, 25, 0x00, 0x32, 0x01, 0x2C, 0x02, 0x58, 3)
                }
                0x20 -> gpioDir = p[0].toInt() and 0xFF
                0x21 -> out = b(gpio)
                0x22 -> gpio = gpio or p[0].toInt()
                0x23 -> gpio = gpio and p[0].toInt().inv()
                0x8F -> { mode = 0; response = null; return }
                else -> status = 0x80
            }
            if (statusOverride >= 0) status = statusOverride
            val r = b(status) + out + b(0)
            var c = cs(r, r.size - 1)
            if (badChecksum) c = c xor 1
            r[r.size - 1] = c.toByte()
            response = r
        }

        @Synchronized
        override fun read(n: Int): ByteArray {
            val out = ByteArray(n) { 0xFF.toByte() }
            response?.let { it.copyInto(out, 0, 0, minOf(n, it.size)) }
            return out
        }

        override fun intPin(): InputPin? = pin
    }

    private class Pin : InputPin {
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        var high = false
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) { this.handler = handler; this.trigger = trigger }
        override fun offEdge(handler: EdgeHandler) { if (this.handler === handler) this.handler = null }
        override fun isHigh() = high
        override fun close() {}
    }

    @Test
    fun initAndRead() {
        val sim = Sim()
        val m = Rrh46410Minimal(sim)
        m.init()
        assertArrayEquals(b(0x00, 0xFF), sim.written[0])
        assertArrayEquals(b(0x11, 0x01, 0xED), sim.written[1])
        assertTrue(sim.requestsOk)
        sim.sample(5)
        var r = m.read()
        assertEquals(2.5, r.iaq, 1e-9)
        assertEquals(0.5, r.tvocMgM3, 1e-9)
        assertEquals(3.0, r.etohPpm, 1e-9)
        assertEquals(600, r.eco2Ppm)
        assertEquals(30, r.relativeIaq)
        assertEquals(5, r.sample)
        assertFalse(r.warmedUp)
        sim.sample(95)
        r = m.read()
        assertTrue(r.warmedUp && r.sample == 100)
        sim.sample(200)
        r = m.read()
        assertTrue(r.warmedUp && r.sample == 44)
    }

    @Test
    fun errors() {
        val bad = Sim().apply { pid = 0x1234 }
        assertThrows(Rrh46410Exception::class.java) { Rrh46410Minimal(bad).init() }
        val retry = Sim().apply { failWrites = 3 }
        Rrh46410Minimal(retry).init()
        assertEquals(1, retry.mode)
        val sim = Sim()
        val m = Rrh46410Minimal(sim)
        m.init()
        sim.sample(1)
        sim.badChecksum = true
        assertThrows(Rrh46410Exception::class.java) { m.read() }
        sim.badChecksum = false
        sim.statusOverride = 0x0A
        assertEquals(0x0A, assertThrows(Rrh46410Exception::class.java) { m.read() }.status)
    }

    @Test
    fun fullApi() {
        val sim = Sim()
        val f = Rrh46410Full(sim)
        f.pollMs = 1
        f.init()
        assertEquals(Rrh46410ProductId(0x2310, 1, 2, 3), f.productId())
        assertArrayEquals(b(1, 2, 3, 4, 5, 6), f.trackingNumber())
        assertEquals(Rrh46410Minimal.MODE_IAQ_2ND_GEN, f.getMode())
        assertNull(f.readResults())
        assertTrue(f.status().notComplete)
        sim.sample(3)
        assertEquals(3, f.readResults()!!.sample)
        assertFalse(f.waitForSample(0.005))
        sim.sample(1)
        assertTrue(f.pollInterrupt())
        assertTrue(f.waitForSample(0.1))
        assertEquals(4, f.lastResult()!!.sample)
        assertFalse(f.pollInterrupt())
        f.setHumidity(50.0)
        assertEquals(128, sim.humidity)
        assertThrows(IllegalArgumentException::class.java) { f.setHumidity(101.0) }
        f.setMode(Rrh46410Minimal.MODE_PBAQ)
        assertEquals(5.0, f.samplePeriod())
        assertEquals(60, f.warmupSamples())
        assertEquals(90.0, f.samplePeriod(Rrh46410Minimal.MODE_ULP))
        assertEquals(10, f.warmupSamples(Rrh46410Minimal.MODE_ULP))
        assertEquals(100, f.warmupSamples(Rrh46410Minimal.MODE_IAQ_2ND_GEN))
        sim.sample(2)
        val p = f.readResults()!!
        assertTrue(p.pbaq)
        assertEquals(0.5, p.tvocMgM3, 1e-9)
        assertThrows(IllegalArgumentException::class.java) { f.setMode(0x04) }
        assertEquals("very good", f.airQualityLabel(1.5))
        assertEquals("good", f.airQualityLabel(2.0))
        assertEquals("medium", f.airQualityLabel(3.9))
        assertEquals("poor", f.airQualityLabel(4.0))
        assertEquals("bad", f.airQualityLabel(5.0))
        f.configGpio(3)
        assertEquals(3, sim.gpioDir)
        f.setGpio(1)
        assertEquals(1, f.getGpio() and 1)
        f.clearGpio(1)
        assertEquals(0, f.getGpio() and 1)
        assertThrows(IllegalArgumentException::class.java) { f.setGpio(4) }
        f.suspend()
        assertEquals(0, sim.mode)
        assertThrows(IllegalArgumentException::class.java) { f.cleanSensor(false) }
        assertArrayEquals(b(sim.gpio), f.sendCommand(0x21, ByteArray(0), 1))
        f.sendCommand(0x55)
        assertTrue(f.status().invalidCommand)
        f.reset()
        assertTrue(sim.written.any { it.contentEquals(b(0x8F, 0x70)) })
        assertEquals(0.0, f.samplePeriod())
    }

    @Test
    fun interrupt() {
        val sim = Sim()
        val pin = Pin()
        val f = Rrh46410Full(sim)
        f.init()
        val hits = mutableListOf<Rrh46410Measurement>()
        f.onInterrupt({ hits.add(it) }, pin)
        assertEquals(EdgeTrigger.RISING, pin.trigger)
        sim.sample(1)
        pin.handler!!.onEdge()
        pin.handler!!.onEdge()
        assertEquals(1, hits.size)
        f.offInterrupt()
        assertNull(pin.handler)

        f.pollMs = 2
        val polled = Collections.synchronizedList(mutableListOf<Rrh46410Measurement>())
        f.onInterrupt({ polled.add(it) }, null)
        sim.sample(1)
        repeat(200) { if (polled.isEmpty()) Thread.sleep(5) }
        f.offInterrupt()
        assertEquals(2, polled[0].sample)

        sim.pin = pin
        assertFalse(f.pollInterrupt())
        pin.high = true
        assertTrue(f.pollInterrupt())
    }
}
