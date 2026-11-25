package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * MPL3115A2 — barometric pressure, altitude and temperature sensor (NXP), minimal driver.
 *
 * One-shot barometer readings with no configuration beyond the connection. I²C address is fixed
 * at 0x60. The constructor does no bus access; [init] checks WHO_AM_I (0xC4), resets the chip,
 * enables the data-ready flags (PT_DATA_CFG = 0x07) and selects barometer mode with oversample
 * ratio 128 (about 512 ms per sample) in STANDBY.
 */
open class Mpl3115a2Minimal(protected val connection: RegisterConnection) {
    companion object {
        const val REG_DR_STATUS   = 0x06
        const val REG_OUT_P_MSB   = 0x01
        const val REG_OUT_P_DELTA = 0x07
        const val REG_WHO_AM_I    = 0x0C
        const val REG_F_STATUS    = 0x0D
        const val REG_F_DATA      = 0x0E
        const val REG_F_SETUP     = 0x0F
        const val REG_SYSMOD      = 0x11
        const val REG_INT_SOURCE  = 0x12
        const val REG_PT_DATA_CFG = 0x13
        const val REG_BAR_IN_MSB  = 0x14
        const val REG_P_TGT_MSB   = 0x16
        const val REG_T_TGT       = 0x18
        const val REG_P_WND_MSB   = 0x19
        const val REG_T_WND       = 0x1B
        const val REG_P_MIN       = 0x1C
        const val REG_T_MIN       = 0x1F
        const val REG_P_MAX       = 0x21
        const val REG_T_MAX       = 0x24
        const val REG_CTRL_REG1   = 0x26
        const val REG_CTRL_REG2   = 0x27
        const val REG_CTRL_REG3   = 0x28
        const val REG_CTRL_REG4   = 0x29
        const val REG_CTRL_REG5   = 0x2A
        const val REG_OFF_P       = 0x2B
        const val REG_OFF_T       = 0x2C
        const val REG_OFF_H       = 0x2D

        const val CHIP_ID    = 0xC4
        const val CTRL1_ALT  = 0x80
        const val CTRL1_RST  = 0x04
        const val CTRL1_OST  = 0x02
        const val CTRL1_SBYB = 0x01
        const val DR_PTDR    = 0x08

        /** Minimum time between samples in ms for OS = 0..7 (oversample ratio 1..128). */
        val OSR_TIME_MS = intArrayOf(6, 10, 18, 34, 66, 130, 258, 512)

        internal fun pressureFrom(b: IntArray, o: Int): Double =
            ((b[o] shl 12) or (b[o + 1] shl 4) or (b[o + 2] shr 4)) / 4.0

        internal fun altitudeFrom(b: IntArray, o: Int): Double =
            ((b[o] shl 8) or b[o + 1]).toShort().toInt() + (b[o + 2] shr 4) / 16.0

        internal fun temperatureFrom(b: IntArray, o: Int): Double =
            b[o].toByte().toInt() + (b[o + 1] shr 4) / 16.0
    }

    protected var os = 7
    protected var alt = false

    protected fun writeReg(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    protected fun writeRegs(reg: Int, vararg values: Int) =
        connection.write(reg, ByteArray(values.size) { values[it].toByte() })

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun readRegs(reg: Int, n: Int): IntArray {
        val b = connection.read(reg, n)
        return IntArray(n) { b[it].toInt() and 0xFF }
    }

    protected fun ctrl1(): Int = (if (alt) CTRL1_ALT else 0) or (os shl 3)

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun softReset() {
        try {
            writeReg(REG_CTRL_REG1, CTRL1_RST)   // the chip may not ACK while rebooting
        } catch (e: IOException) {
            // expected
        }
        repeat(100) {
            sleepMs(2)
            try {
                if (readReg(REG_CTRL_REG1) and CTRL1_RST == 0) return
            } catch (e: IOException) {
                // still rebooting
            }
        }
        throw IOException("MPL3115A2 reset timeout")
    }

    protected fun applyConfig() {
        writeReg(REG_PT_DATA_CFG, 0x07)
        writeReg(REG_CTRL_REG1, ctrl1())
    }

    protected fun waitPtdr() {
        val deadline = System.currentTimeMillis() + OSR_TIME_MS[os] * 2L + 1000
        while (true) {
            sleepMs(3)
            if (readReg(REG_DR_STATUS) and DR_PTDR != 0) return
            if (System.currentTimeMillis() > deadline) throw IOException("MPL3115A2 data not ready")
        }
    }

    protected fun oneShot(): IntArray {
        writeReg(REG_CTRL_REG1, ctrl1())                  // OST must be 0 before it is set
        writeReg(REG_CTRL_REG1, ctrl1() or CTRL1_OST)
        waitPtdr()
        return readRegs(REG_OUT_P_MSB, 5)
    }

    /**
     * Check WHO_AM_I, soft reset and apply the default configuration.
     *
     * @throws IOException on bus error, a wrong chip ID or a reset timeout
     */
    @Throws(IOException::class)
    fun init() {
        val id = readReg(REG_WHO_AM_I)
        if (id != CHIP_ID) throw IOException("Unexpected chip ID 0x%02X, expected 0xC4".format(id))
        softReset()
        os = 7
        alt = false
        applyConfig()
    }

    /**
     * Run one conversion.
     *
     * @return `[pressure in Pa, temperature in °C]`
     * @throws IOException on bus error or timeout
     */
    @Throws(IOException::class)
    open fun read(): DoubleArray {
        val raw = oneShot()
        return doubleArrayOf(pressureFrom(raw, 0), temperatureFrom(raw, 3))
    }

    /** Run one conversion and return the pressure in pascals. */
    @Throws(IOException::class)
    fun readPressure(): Double = read()[0]

    /** Run one conversion and return the temperature in degrees Celsius. */
    @Throws(IOException::class)
    fun readTemperature(): Double = read()[1]
}
