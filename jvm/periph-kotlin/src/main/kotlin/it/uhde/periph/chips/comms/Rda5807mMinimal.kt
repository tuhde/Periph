package it.uhde.periph.chips.comms

import it.uhde.periph.transport.Transport
import kotlin.math.roundToInt

/**
 * RDA5807M — single-chip FM stereo radio tuner with I²C interface (minimal driver).
 *
 * Tunes to a station, adjusts volume, mutes, and seeks the next station. No
 * configuration required beyond the transport.
 *
 * Unlike most chips in this project, the RDA5807M has no register-pointer
 * byte: writes always start at the fixed register 0x02 and reads always start
 * at the fixed register 0x0A. This driver keeps an in-memory shadow of
 * registers 0x02-0x07 (6 big-endian 16-bit words) and rewrites all of them on
 * every change, since the chip cannot be told to start a write anywhere else.
 *
 * Fixed I²C address: 0x10.
 *
 * @param transport    I²C transport bound to address 0x10
 * @param frequencyMhz initial frequency in MHz (default 100.0)
 * @param volume       initial volume, 0 (mute) to 15 (max) (default 8)
 */
open class Rda5807mMinimal @JvmOverloads constructor(
    protected val transport: Transport,
    frequencyMhz: Double = 100.0,
    volume: Int = 8
) {
    protected var band: Int = BAND_WORLD
    protected var space: Int = SPACE_100K
    protected var eastEurope50m: Boolean = false

    protected val regs = IntArray(6)

    companion object {
        const val BAND_US_EUROPE = 0
        const val BAND_JAPAN = 1
        const val BAND_WORLD = 2
        const val BAND_EAST_EUROPE = 3

        const val SPACE_100K = 0
        const val SPACE_200K = 1
        const val SPACE_50K = 2
        const val SPACE_25K = 3

        private val BAND_BASE_KHZ = intArrayOf(87000, 76000, 76000, 65000)
        private val SPACE_KHZ = intArrayOf(100, 200, 50, 25)

        private const val STC_TIMEOUT_MS = 500
        private const val STC_POLL_MS = 1L

        const val DHIZ = 0x8000
        const val DMUTE = 0x4000
        const val MONO = 0x2000
        const val BASS = 0x1000
        const val SEEKUP = 0x0200
        const val SEEK = 0x0100
        const val SKMODE = 0x0080
        const val RDS_EN = 0x0008
        const val NEW_METHOD = 0x0004
        const val SOFT_RESET = 0x0002
        const val ENABLE = 0x0001

        const val TUNE = 0x0010

        const val DE = 0x0800
        const val SOFTMUTE_EN = 0x0200
        const val AFCD = 0x0100

        const val INT_MODE = 0x8000

        const val BAND_65M_50M = 0x0200

        const val RDSR = 0x8000
        const val STC = 0x4000
        const val SF = 0x2000
        const val ST = 0x0400

        const val FM_TRUE = 0x0100
        const val FM_READY = 0x0080

        private fun freqToChan(band: Int, space: Int, eastEurope50m: Boolean, frequencyMhz: Double): Int {
            val base = if (band == 3 && eastEurope50m) 50000 else BAND_BASE_KHZ[band]
            val freqKhz = (frequencyMhz * 1000.0).roundToInt()
            var chan = ((freqKhz - base).toDouble() / SPACE_KHZ[space]).roundToInt()
            if (chan < 0) chan = 0
            if (chan > 1023) chan = 1023
            return chan
        }

        private fun chanToFreq(band: Int, space: Int, eastEurope50m: Boolean, chan: Int): Double {
            val base = if (band == 3 && eastEurope50m) 50000 else BAND_BASE_KHZ[band]
            return (base + chan * SPACE_KHZ[space]) / 1000.0
        }
    }

    init {
        val ctrl = DHIZ or DMUTE or SKMODE or NEW_METHOD or ENABLE
        val chan = freqToChan(band, space, eastEurope50m, frequencyMhz)
        val chanReg = (chan shl 6) or TUNE or (band shl 2) or space
        val r4 = SOFTMUTE_EN or DE
        val r5 = INT_MODE or (8 shl 8) or (volume and 0x0F)
        val r6 = 0x0000
        val r7 = (16 shl 10) or BAND_65M_50M or 0x0002

        regs[0] = ctrl
        regs[1] = chanReg
        regs[2] = r4
        regs[3] = r5
        regs[4] = r6
        regs[5] = r7

        writeRegs()
        waitStc()
        regs[1] = regs[1] and TUNE.inv()
    }

    protected fun writeRegs() {
        val buf = ByteArray(12)
        for (i in 0 until 6) {
            buf[i * 2] = (regs[i] shr 8).toByte()
            buf[i * 2 + 1] = (regs[i] and 0xFF).toByte()
        }
        transport.write(buf)
    }

    protected fun readStatus(n: Int): IntArray {
        val buf = transport.read(n)
        val words = IntArray(n / 2)
        for (i in words.indices) {
            words[i] = ((buf[i * 2].toInt() and 0xFF) shl 8) or (buf[i * 2 + 1].toInt() and 0xFF)
        }
        return words
    }

    protected fun waitStc(): Int {
        var elapsed = 0
        while (elapsed < STC_TIMEOUT_MS) {
            val statusA = readStatus(2)[0]
            if (statusA and STC != 0) return statusA
            Thread.sleep(STC_POLL_MS)
            elapsed += STC_POLL_MS.toInt()
        }
        return 0
    }

    /** Tune to a frequency, blocking until the tune completes. */
    fun setFrequency(frequencyMhz: Double) {
        val chan = freqToChan(band, space, eastEurope50m, frequencyMhz)
        regs[1] = (chan shl 6) or TUNE or (band shl 2) or space
        writeRegs()
        waitStc()
        regs[1] = regs[1] and TUNE.inv()
    }

    /** @return currently tuned frequency in MHz, derived from READCHAN */
    fun frequency(): Double {
        val statusA = readStatus(2)[0]
        val readchan = statusA and 0x03FF
        return chanToFreq(band, space, eastEurope50m, readchan)
    }

    /** Set the output volume, 0 (mute) to 15 (max), logarithmic scale. */
    fun setVolume(level: Int) {
        regs[3] = (regs[3] and 0x000F.inv()) or (level and 0x0F)
        writeRegs()
    }

    /** Mute (true) or unmute (false) the audio output. */
    fun mute(enable: Boolean) {
        if (enable) regs[0] = regs[0] and DMUTE.inv() else regs[0] = regs[0] or DMUTE
        writeRegs()
    }

    /**
     * Seek to the next station, blocking until the seek completes.
     *
     * @return the new frequency in MHz, or `null` if the seek failed (SF flag set)
     */
    fun seek(up: Boolean = true): Double? {
        if (up) regs[0] = regs[0] or SEEKUP else regs[0] = regs[0] and SEEKUP.inv()
        regs[0] = regs[0] or SEEK
        writeRegs()
        val statusA = waitStc()
        regs[0] = regs[0] and SEEK.inv()
        writeRegs()

        if (statusA and SF != 0) return null
        val readchan = statusA and 0x03FF
        return chanToFreq(band, space, eastEurope50m, readchan)
    }
}
