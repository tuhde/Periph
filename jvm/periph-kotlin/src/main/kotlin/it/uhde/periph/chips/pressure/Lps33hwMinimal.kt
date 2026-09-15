package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * LPS33HW — water-resistant MEMS absolute pressure sensor (minimal driver).
 *
 * Reads pressure in pascals and temperature in degrees Celsius via I²C.
 * The chip ID register is verified to be `0xB1`. With BDU=1 the output
 * register latch only releases after `PRESS_OUT_H` (0x2A) has been read,
 * so the driver always reads a 5-byte burst from PRESS_OUT_XL through
 * TEMP_OUT_H to release the latch correctly.
 *
 * Configurable I²C address: `0x5C` (SA0=GND, default) or `0x5D`
 * (SA0=VDD).
 *
 * Default settings: ODR=1 Hz (CTRL_REG1=0x12), BDU=1, EN_LPFP=0,
 * IF_ADD_INC=1.
 */
open class Lps33hwMinimal(
    protected val connection: Connection
) {

    companion object {
        // Register addresses
        const val REG_INTERRUPT_CFG = 0x0B
        const val REG_THS_P_L       = 0x0C
        const val REG_THS_P_H       = 0x0D
        const val REG_WHO_AM_I      = 0x0F
        const val REG_CTRL_REG1     = 0x10
        const val REG_CTRL_REG2     = 0x11
        const val REG_CTRL_REG3     = 0x12
        const val REG_FIFO_CTRL     = 0x14
        const val REG_REF_P_XL      = 0x15
        const val REG_REF_P_L       = 0x16
        const val REG_REF_P_H       = 0x17
        const val REG_RPDS_L        = 0x18
        const val REG_RPDS_H        = 0x19
        const val REG_RES_CONF      = 0x1A
        const val REG_INT_SOURCE    = 0x25
        const val REG_FIFO_STATUS   = 0x26
        const val REG_STATUS        = 0x27
        const val REG_PRESS_XL      = 0x28
        const val REG_PRESS_L       = 0x29
        const val REG_PRESS_H       = 0x2A
        const val REG_TEMP_L        = 0x2B
        const val REG_TEMP_H        = 0x2C
        const val REG_LPFP_RES      = 0x33

        /** Expected chip ID. */
        const val CHIP_ID = 0xB1

        /** Status register bits. */
        const val STATUS_P_DA = 0x01
        const val STATUS_T_DA = 0x02

        /** Default CTRL_REG1 (ODR=1 Hz, BDU=1). */
        const val CTRL_REG1_DEFAULT = 0x12
        /** CTRL_REG2 with SWRESET=1. */
        const val CTRL_REG2_RESET   = 0x04
        /** Default CTRL_REG2 after reset (IF_ADD_INC=1). */
        const val CTRL_REG2_DEFAULT = 0x10
    }

    init {
        // Verify chip ID.
        val id = connection.writeRead(byteArrayOf(REG_WHO_AM_I.toByte()), 1)
        val chipId = id[0].toInt() and 0xFF
        if (chipId != CHIP_ID) {
            throw IOException(
                "LPS33HW not found: expected 0xB1, got 0x${chipId.toString(16)}"
            )
        }

        // Software reset, then restore IF_ADD_INC=1, then default CTRL_REG1.
        writeReg(REG_CTRL_REG2, CTRL_REG2_RESET)
        Thread.sleep(1)
        writeReg(REG_CTRL_REG2, CTRL_REG2_DEFAULT)
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)
    }

    /**
     * Write a single byte to a register.
     *
     * @param reg   register address
     * @param value byte value to write
     */
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    /**
     * Read a single byte from a register.
     *
     * @param reg register address
     * @return raw byte
     */
    protected fun readReg(reg: Int): Int {
        val b = connection.writeRead(byteArrayOf(reg.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Wait for the given STATUS bits to be set.
     */
    protected fun waitStatus(mask: Int) {
        for (i in 0 until 50) {
            val status = readReg(REG_STATUS)
            if ((status and mask) == mask) return
            Thread.sleep(5)
        }
    }

    /**
     * Burst-read PRESS_OUT_XL..TEMP_OUT_H (5 bytes) and return both the
     * calibrated pressure and temperature in one tuple.
     *
     * With BDU=1 the latch releases only after PRESS_OUT_H has been read,
     * which falls inside the 5-byte burst.
     *
     * @return Pair(pressure_Pa, temperature_C)
     */
    protected fun readPressTemp(): Pair<Double, Double> {
        waitStatus(STATUS_P_DA or STATUS_T_DA)
        val raw = connection.writeRead(byteArrayOf(REG_PRESS_XL.toByte()), 5)
        var rawPress = (raw[0].toInt() and 0xFF) or
                       ((raw[1].toInt() and 0xFF) shl 8) or
                       ((raw[2].toInt() and 0xFF) shl 16)
        if (rawPress >= 0x800000) rawPress -= 0x1000000
        var rawTemp = (raw[3].toInt() and 0xFF) or ((raw[4].toInt() and 0xFF) shl 8)
        if (rawTemp >= 0x8000) rawTemp -= 0x10000
        val pressure_Pa = rawPress * 100.0 / 4096.0
        val temperature_C = rawTemp / 100.0
        return Pair(pressure_Pa, temperature_C)
    }

    /**
     * Read the calibrated absolute pressure.
     *
     * @return pressure in Pa
     */
    fun pressure(): Double = readPressTemp().first

    /**
     * Read the calibrated temperature.
     *
     * @return temperature in °C
     */
    fun temperature(): Double = readPressTemp().second
}