package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * LPS22DF absolute pressure and temperature sensor (STMicroelectronics).
 *
 * Provides pressure (Pa) and temperature (°C) readings via I²C with no
 * configuration beyond the connection. I²C address is 0x5C (SA0=GND) or
 * 0x5D (SA0=VDDIO). The chip has built-in factory calibration; no user
 * calibration read step is required.
 *
 * Pressure is 24-bit two's complement at 4096 LSB/hPa; temperature is
 * 16-bit two's complement at 100 LSB/°C.
 *
 * Default settings: ODR=10 Hz, AVG=4 samples, BDU on, low-pass filter off,
 * FIFO bypass mode.
 */
open class Lps22dfMinimal(
    protected val conn: Connection,
    protected val addr: Int = 0x5C,
    protected val busType: Int = BUS_I2C
) {
    companion object {
        /** Bus type: I²C — register addresses used unmasked. */
        const val BUS_I2C = 0
        /** Bus type: SPI — write addresses have bit 7 cleared; reads stay unmasked. */
        const val BUS_SPI = 1

        // Register addresses
        protected const val REG_INTERRUPT_CFG = 0x0B
        protected const val REG_THS_P_L       = 0x0C
        protected const val REG_THS_P_H       = 0x0D
        protected const val REG_WHO_AM_I      = 0x0F
        protected const val REG_CTRL_REG1     = 0x10
        protected const val REG_CTRL_REG2     = 0x11
        protected const val REG_CTRL_REG3     = 0x12
        protected const val REG_CTRL_REG4     = 0x13
        protected const val REG_FIFO_CTRL     = 0x14
        protected const val REG_FIFO_WTM      = 0x15
        protected const val REG_REF_P_L       = 0x16
        protected const val REG_REF_P_H       = 0x17
        protected const val REG_RPDS_L        = 0x1A
        protected const val REG_RPDS_H        = 0x1B
        protected const val REG_INT_SOURCE    = 0x24
        protected const val REG_FIFO_STATUS1  = 0x25
        protected const val REG_STATUS        = 0x27
        protected const val REG_PRESS_OUT_XL  = 0x28
        protected const val REG_TEMP_OUT_L    = 0x2B
        protected const val REG_FIFO_PRESS_XL = 0x78

        /** Expected chip ID for the LPS22DF. */
        protected const val CHIP_ID = 0xB4

        /** Status flag: pressure data available. */
        protected const val STATUS_P_DA = 0x01
    }

    init {
        val who = conn.writeRead(byteArrayOf(REG_WHO_AM_I.toByte()), 1)
        if ((who[0].toInt() and 0xFF) != CHIP_ID) {
            throw IOException(
                "LPS22DF not found: expected WHO_AM_I 0x${CHIP_ID.toString(16)}, got 0x${(who[0].toInt() and 0xFF).toString(16)}"
            )
        }
        writeReg(REG_CTRL_REG2, 0x04)
        Thread.sleep(1)
        writeReg(REG_CTRL_REG1, (3 shl 3) or 0)
        writeReg(REG_CTRL_REG2, 0x08)
    }

    /** Write a single byte to a register, applying the SPI write-address mask when needed. */
    protected fun writeReg(reg: Int, value: Int) {
        val a = if (busType == BUS_SPI) (reg and 0x7F) else reg
        conn.write(byteArrayOf(a.toByte(), value.toByte()))
    }

    /** Read bytes from a register, applying the SPI read-address mask when needed. */
    protected fun readReg(reg: Int, len: Int): ByteArray {
        val a = if (busType == BUS_SPI) (reg and 0x7F) else reg
        return conn.writeRead(byteArrayOf(a.toByte()), len)
    }

    /** Poll STATUS until P_DA is set. */
    protected fun waitPDa() {
        while (true) {
            val status = readReg(REG_STATUS, 1)
            if ((status[0].toInt() and STATUS_P_DA) != 0) return
            Thread.sleep(1)
        }
    }

    /**
     * Read absolute pressure.
     *
     * Polls STATUS.P_DA then burst-reads PRESS_OUT_XL..H. Sign-extends the
     * 24-bit two's complement value and converts to pascals (4096 LSB/hPa).
     *
     * @return pressure in pascals
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun pressure(): Double {
        waitPDa()
        val raw = readReg(REG_PRESS_OUT_XL, 3)
        var value = (raw[0].toInt() and 0xFF) or
                    ((raw[1].toInt() and 0xFF) shl 8) or
                    ((raw[2].toInt() and 0xFF) shl 16)
        if ((value and 0x800000) != 0) value -= 0x1000000
        return (value / 4096.0) * 100.0
    }

    /**
     * Read temperature.
     *
     * Reads TEMP_OUT_L..H. Sign-extends the 16-bit two's complement value
     * and converts to °C (100 LSB/°C).
     *
     * @return temperature in degrees Celsius
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun temperature(): Double {
        val raw = readReg(REG_TEMP_OUT_L, 2)
        val s = ((raw[0].toInt() and 0xFF) shl 0) or ((raw[1].toInt() and 0xFF) shl 8)
        return s.toShort().toDouble() / 100.0
    }

    /** @return chip ID register value (expected 0xB4). */
    @Throws(IOException::class)
    fun whoAmI(): Int {
        val v = readReg(REG_WHO_AM_I, 1)
        return v[0].toInt() and 0xFF
    }
}