package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * SPL06-007 — barometric pressure and temperature sensor (Goertek), minimal driver.
 *
 * One call returns compensated temperature and pressure. The chip stays in standby between
 * readings; each [read] runs one temperature and one pressure measurement in command mode. The
 * constructor does no bus access; [init] verifies ID (0x10), waits for SENSOR_RDY / COEF_RDY, reads
 * the 18 coefficient bytes and writes TMP_CFG = 0x80 (external temperature sensor, 1×),
 * PRS_CFG = 0x04 (16×, standard precision) and CFG_REG = 0x04 (P_SHIFT). I²C address 0x77
 * (SDO high or open) or 0x76 (SDO low).
 *
 * @param connection I²C register connection bound to address 0x77 or 0x76
 */
open class Spl06Minimal(protected val connection: RegisterConnection) {
    companion object {
        const val REG_PRS_B2   = 0x00
        const val REG_TMP_B2   = 0x03
        const val REG_PRS_CFG  = 0x06
        const val REG_TMP_CFG  = 0x07
        const val REG_MEAS_CFG = 0x08
        const val REG_CFG_REG  = 0x09
        const val REG_INT_STS  = 0x0A
        const val REG_FIFO_STS = 0x0B
        const val REG_RESET    = 0x0C
        const val REG_ID       = 0x0D
        const val REG_COEF     = 0x10

        const val CHIP_ID         = 0x10
        const val MEAS_COEF_RDY   = 0x80
        const val MEAS_SENSOR_RDY = 0x40
        const val MEAS_TMP_RDY    = 0x20
        const val MEAS_PRS_RDY    = 0x10
        const val CMD_IDLE        = 0x00
        const val CMD_PRESSURE    = 0x01
        const val CMD_TEMPERATURE = 0x02
        const val CFG_T_SHIFT     = 0x08
        const val CFG_P_SHIFT     = 0x04
        const val TMP_EXT         = 0x80

        /** Scale factors kP / kT indexed by log2(oversampling) (datasheet Table 4). */
        val SCALE = doubleArrayOf(524288.0, 1572864.0, 3670016.0, 7864320.0, 253952.0, 516096.0, 1040384.0, 2088960.0)
        /** Typical measurement time in ms indexed by log2(oversampling), rounded up. */
        val MEAS_MS = longArrayOf(4, 6, 9, 15, 28, 54, 105, 207)

        internal fun signed(value: Int, bits: Int): Int = (value shl (32 - bits)) shr (32 - bits)

        internal fun raw24(b: ByteArray, o: Int): Int =
            signed(((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF), 24)

        internal fun compT(c: Coefficients, t: Double): Double = c.c0 * 0.5 + c.c1 * t

        internal fun compP(c: Coefficients, p: Double, t: Double): Double =
            c.c00 + p * (c.c10 + p * (c.c20 + p * c.c30)) + t * c.c01 + t * p * (c.c11 + p * c.c21)
    }

    /** One compensated reading: temperature in °C and pressure in Pa. */
    data class Measurement(val temperatureC: Double, val pressurePa: Double)

    /** Decoded calibration coefficients (two's complement, sign-extended). */
    data class Coefficients(
        val c0: Int, val c1: Int, val c00: Int, val c10: Int, val c01: Int,
        val c11: Int, val c20: Int, val c21: Int, val c30: Int,
    )

    protected var coef = Coefficients(0, 0, 0, 0, 0, 0, 0, 0, 0)
    protected var pOs = 4
    protected var tOs = 0
    protected var cfg = CFG_P_SHIFT
    protected var lastTraw = 0

    protected fun writeReg(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    /**
     * Verify the chip, read the calibration coefficients and apply the defaults.
     *
     * @throws IOException on I²C error, a wrong ID or a chip that does not become ready
     */
    fun init() {
        val id = readReg(REG_ID)
        if (id != CHIP_ID) throw IOException("Unexpected chip ID 0x%02X, expected 0x10".format(id))
        waitReady()
        readCoefficients()
        applyDefaults()
    }

    protected fun waitReady() {
        val mask = MEAS_COEF_RDY or MEAS_SENSOR_RDY
        repeat(40) {
            try {
                if (readReg(REG_MEAS_CFG) and mask == mask) return
            } catch (e: IOException) {
                // the chip may not acknowledge right after a reset
            }
            Thread.sleep(5)
        }
        throw IOException("SPL06 not ready")
    }

    protected fun readCoefficients() {
        val b = connection.read(REG_COEF, 18).map { it.toInt() and 0xFF }
        coef = Coefficients(
            signed((b[0] shl 4) or (b[1] shr 4), 12),
            signed(((b[1] and 0x0F) shl 8) or b[2], 12),
            signed((b[3] shl 12) or (b[4] shl 4) or (b[5] shr 4), 20),
            signed(((b[5] and 0x0F) shl 16) or (b[6] shl 8) or b[7], 20),
            signed((b[8] shl 8) or b[9], 16),
            signed((b[10] shl 8) or b[11], 16),
            signed((b[12] shl 8) or b[13], 16),
            signed((b[14] shl 8) or b[15], 16),
            signed((b[16] shl 8) or b[17], 16),
        )
    }

    protected fun applyDefaults() {
        pOs = 4
        tOs = 0
        cfg = CFG_P_SHIFT
        writeReg(REG_TMP_CFG, TMP_EXT)
        writeReg(REG_PRS_CFG, 0x04)
        writeReg(REG_CFG_REG, cfg)
    }

    protected fun measure(command: Int, flag: Int, os: Int): Int {
        writeReg(REG_MEAS_CFG, command)
        Thread.sleep(MEAS_MS[os])
        val deadline = System.currentTimeMillis() + MEAS_MS[os] + 50
        while (readReg(REG_MEAS_CFG) and flag == 0) {
            if (System.currentTimeMillis() > deadline) throw IOException("SPL06 measurement timeout")
            Thread.sleep(2)
        }
        return raw24(connection.read(if (command == CMD_PRESSURE) REG_PRS_B2 else REG_TMP_B2, 3), 0)
    }

    protected fun measureRaw(): IntArray {
        val traw = measure(CMD_TEMPERATURE, MEAS_TMP_RDY, tOs)
        val praw = measure(CMD_PRESSURE, MEAS_PRS_RDY, pOs)
        lastTraw = traw
        return intArrayOf(praw, traw)
    }

    protected fun temperature(traw: Int): Double = compT(coef, traw / SCALE[tOs])

    protected fun pressure(praw: Int, traw: Int): Double = compP(coef, praw / SCALE[pOs], traw / SCALE[tOs])

    /**
     * Measure temperature, then pressure (command mode), and compensate both.
     *
     * @return temperature in °C and pressure in Pa
     */
    open fun read(): Measurement {
        val (praw, traw) = measureRaw()
        return Measurement(temperature(traw), pressure(praw, traw))
    }
}
