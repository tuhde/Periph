package it.uhde.periph.chips.comms

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class Rda5807mTest {

    // Register bit constants and frequency<->channel math, mirrored from
    // RDA5807MMinimal/RDA5807MFull's companion objects (protected there, so
    // re-declared here to build expected values). BAND_*/SPACE_* are public
    // on the driver itself so those are referenced directly.
    private val BAND_BASE_KHZ = intArrayOf(87000, 76000, 76000, 65000)
    private val SPACE_KHZ = intArrayOf(100, 200, 50, 25)

    private val DHIZ = 0x8000
    private val DMUTE = 0x4000
    private val MONO = 0x2000
    private val BASS = 0x1000
    private val SEEKUP = 0x0200
    private val SEEK = 0x0100
    private val SKMODE = 0x0080
    private val RDS_EN = 0x0008
    private val NEW_METHOD = 0x0004
    private val SOFT_RESET = 0x0002
    private val ENABLE = 0x0001
    private val TUNE = 0x0010
    private val DE = 0x0800
    private val SOFTMUTE_EN = 0x0200
    private val AFCD = 0x0100
    private val INT_MODE = 0x8000
    private val BAND_65M_50M = 0x0200
    private val RDSR = 0x8000
    private val STC = 0x4000
    private val SF = 0x2000
    private val ST = 0x0400
    private val FM_TRUE = 0x0100
    private val FM_READY = 0x0080

    private fun freqToChan(band: Int, space: Int, east50: Boolean, freqMhz: Double): Int {
        val base = if (band == 3 && east50) 50000 else BAND_BASE_KHZ[band]
        val freqKhz = (freqMhz * 1000.0).roundToInt()
        var chan = ((freqKhz - base).toDouble() / SPACE_KHZ[space]).roundToInt()
        if (chan < 0) chan = 0
        if (chan > 1023) chan = 1023
        return chan
    }

    private fun chanToFreq(band: Int, space: Int, east50: Boolean, chan: Int): Double {
        val base = if (band == 3 && east50) 50000 else BAND_BASE_KHZ[band]
        return (base + chan * SPACE_KHZ[space]) / 1000.0
    }

    private fun regsBytes(regs: IntArray): ByteArray {
        val buf = ByteArray(12)
        for (i in 0 until 6) {
            buf[i * 2] = (regs[i] shr 8).toByte()
            buf[i * 2 + 1] = (regs[i] and 0xFF).toByte()
        }
        return buf
    }

    private fun statusBytes(vararg words: Int): ByteArray {
        val buf = ByteArray(words.size * 2)
        for (i in words.indices) {
            buf[i * 2] = (words[i] shr 8).toByte()
            buf[i * 2 + 1] = (words[i] and 0xFF).toByte()
        }
        return buf
    }

    /** Bundle returned by [newSensor]: the mock, the driver under test, and
     * the expected post-init shadow register array (TUNE already cleared,
     * mirroring what the driver does once it observes STC). RDA5807MFull is
     * the actual driver class (unusual all-caps casing, unlike this test's
     * own Rda5807mTest name - see the file-naming note in the task brief). */
    private class Fixture(
        val connection: MockConnection,
        val sensor: RDA5807MFull,
        var regs: IntArray,
        val band: Int,
        val space: Int,
        val east50: Boolean,
    )

    /** Construct a fresh RDA5807MFull with a queued STC-set status so the
     * blocking waitStc() inside the constructor resolves on its first poll. */
    private fun newSensor(frequencyMhz: Double = 100.0, volume: Int = 8): Fixture {
        val connection = MockConnection()
        connection.queueRead(statusBytes(STC))
        val band = RDA5807MMinimal.BAND_WORLD
        val space = RDA5807MMinimal.SPACE_100K
        val east50 = false
        val chan0 = freqToChan(band, space, east50, frequencyMhz)
        val regs = intArrayOf(
            DHIZ or DMUTE or SKMODE or NEW_METHOD or ENABLE,
            (chan0 shl 6) or TUNE or (band shl 2) or space,
            SOFTMUTE_EN or DE,
            INT_MODE or (8 shl 8) or (volume and 0x0F),
            0x0000,
            (16 shl 10) or BAND_65M_50M or 0x0002,
        )
        val sensor = RDA5807MFull(connection, frequencyMhz, volume)
        regs[1] = regs[1] and TUNE.inv()
        return Fixture(connection, sensor, regs, band, space, east50)
    }

    private fun lastWrite(connection: MockConnection): ByteArray {
        val writes = connection.writes()
        return writes[writes.size - 1]
    }

    @Test
    fun initWritesRegs() {
        val f = newSensor()
        val expected = f.regs.clone()
        expected[1] = expected[1] or TUNE // write happened before the shadow TUNE bit was cleared
        assertArrayEquals(regsBytes(expected), f.connection.writes()[0])
    }

    @Test
    fun frequency() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(250))
        val freq = f.sensor.frequency()
        assertEquals(chanToFreq(f.band, f.space, f.east50, 250), freq)
    }

    @Test
    fun setFrequencyWrites() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(STC))
        f.sensor.setFrequency(103.5)
        val chan1 = freqToChan(f.band, f.space, f.east50, 103.5)
        f.regs[1] = (chan1 shl 6) or TUNE or (f.band shl 2) or f.space
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))
    }

    @Test
    fun setVolume() {
        val f = newSensor()
        f.sensor.setVolume(5)
        f.regs[3] = (f.regs[3] and 0x000F.inv()) or (5 and 0x0F)
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))
    }

    @Test
    fun mute() {
        val f = newSensor()
        f.sensor.mute(true)
        f.regs[0] = f.regs[0] and DMUTE.inv()
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))
        f.sensor.mute(false)
        f.regs[0] = f.regs[0] or DMUTE
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))
    }

    @Test
    fun seekUpFound() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(STC or 300))
        val result = f.sensor.seek(true)
        f.regs[0] = f.regs[0] or SEEKUP
        f.regs[0] = f.regs[0] or SEEK
        val firstWrite = regsBytes(f.regs)
        f.regs[0] = f.regs[0] and SEEK.inv()
        val secondWrite = regsBytes(f.regs)
        val writes = f.connection.writes()
        assertArrayEquals(firstWrite, writes[writes.size - 2])
        assertArrayEquals(secondWrite, writes[writes.size - 1])
        assertEquals(chanToFreq(f.band, f.space, f.east50, 300), result)
    }

    @Test
    fun seekFails() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(STC or SF))
        val result = f.sensor.seek(false)
        assertNull(result)
    }

    @Test
    fun configureRetunes() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(500)) // configure() reads current frequency() first
        val currentFreq = chanToFreq(f.band, f.space, f.east50, 500)
        f.connection.queueRead(statusBytes(STC)) // for the resulting retune's waitStc

        f.sensor.configure(
            band = RDA5807MMinimal.BAND_US_EUROPE, space = RDA5807MMinimal.SPACE_50K,
            deEmphasis = false, seekThreshold = 10, seekMode = false, clkMode = 3, afcDisable = true,
        )

        val band = RDA5807MMinimal.BAND_US_EUROPE
        val space = RDA5807MMinimal.SPACE_50K
        f.regs[2] = f.regs[2] and DE.inv()
        f.regs[2] = f.regs[2] or AFCD
        f.regs[3] = (f.regs[3] and 0x0F00.inv()) or ((10 and 0x0F) shl 8)
        f.regs[0] = f.regs[0] and SKMODE.inv()
        f.regs[0] = (f.regs[0] and 0x0070.inv()) or ((3 and 0x07) shl 4)
        val chan2 = freqToChan(band, space, f.east50, currentFreq)
        f.regs[1] = (chan2 shl 6) or TUNE or (band shl 2) or space
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))
    }

    @Test
    fun configureNoRetune() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(0)) // configure() still reads frequency() first
        f.sensor.configure(seekThreshold = 4)
        f.regs[3] = (f.regs[3] and 0x0F00.inv()) or ((4 and 0x0F) shl 8)
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))
    }

    @Test
    fun bassMonoSoftmuteRds() {
        val f = newSensor()

        f.sensor.setBassBoost(true)
        f.regs[0] = f.regs[0] or BASS
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))

        f.sensor.setMono(true)
        f.regs[0] = f.regs[0] or MONO
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))

        f.sensor.setSoftmute(false)
        f.regs[2] = f.regs[2] and SOFTMUTE_EN.inv()
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))

        f.sensor.enableRds(true)
        f.regs[0] = f.regs[0] or RDS_EN
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))
    }

    @Test
    fun rdsReadyAndGroup() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(RDSR))
        assertTrue(f.sensor.rdsReady())
        f.connection.queueRead(statusBytes(0))
        assertTrue(!f.sensor.rdsReady())

        f.connection.queueRead(statusBytes(RDSR, 0, 0x1122, 0x3344, 0x5566, 0x7788))
        val group = f.sensor.readRdsGroup()
        assertArrayEquals(intArrayOf(0x1122, 0x3344, 0x5566, 0x7788), group)
        f.connection.queueRead(statusBytes(0, 0, 0, 0, 0, 0))
        assertNull(f.sensor.readRdsGroup())
    }

    @Test
    fun statusFlags() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(ST))
        assertTrue(f.sensor.isStereo())
        f.connection.queueRead(statusBytes(0, FM_TRUE))
        assertTrue(f.sensor.isStation())
        f.connection.queueRead(statusBytes(0, FM_READY))
        assertTrue(f.sensor.isReady())
        f.connection.queueRead(statusBytes(0, (100 shl 9) and 0xFFFF))
        assertEquals(100, f.sensor.signalStrength())
    }

    @Test
    fun standby() {
        val f = newSensor()
        f.sensor.standby(true)
        f.regs[0] = f.regs[0] and ENABLE.inv()
        assertArrayEquals(regsBytes(f.regs), lastWrite(f.connection))

        f.connection.queueRead(statusBytes(STC)) // standby(false)'s internal setFrequency's waitStc
        f.sensor.standby(false)
        f.regs[0] = f.regs[0] or ENABLE
        val enableWrite = regsBytes(f.regs)
        val chan3 = freqToChan(f.band, f.space, f.east50, 100.0) // newSensor()'s default frequency, unchanged so far
        f.regs[1] = (chan3 shl 6) or TUNE or (f.band shl 2) or f.space
        val retuneWrite = regsBytes(f.regs)
        val writes = f.connection.writes()
        assertArrayEquals(enableWrite, writes[writes.size - 2])
        assertArrayEquals(retuneWrite, writes[writes.size - 1])
    }

    @Test
    fun softReset() {
        val f = newSensor()
        f.connection.queueRead(statusBytes(STC)) // softReset()'s internal setFrequency's waitStc
        f.sensor.softReset()
        f.regs[0] = f.regs[0] or SOFT_RESET
        val setWrite = regsBytes(f.regs)
        f.regs[0] = f.regs[0] and SOFT_RESET.inv()
        val clearWrite = regsBytes(f.regs)
        val chan4 = freqToChan(f.band, f.space, f.east50, 100.0) // newSensor()'s default frequency, unchanged so far
        f.regs[1] = (chan4 shl 6) or TUNE or (f.band shl 2) or f.space
        val retuneWrite = regsBytes(f.regs)
        val writes = f.connection.writes()
        assertArrayEquals(setWrite, writes[writes.size - 3])
        assertArrayEquals(clearWrite, writes[writes.size - 2])
        assertArrayEquals(retuneWrite, writes[writes.size - 1])
    }
}
