package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * BMP581 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * The BMP581 performs factory calibration internally and outputs
 * already-compensated Pa and °C values, so unlike the BMP280 this driver
 * has no calibration NVM to read and no compensation math — just burst
 * reads of the data registers and fixed-point decode.
 *
 * @property connection Configured I²C connection bound to the device.
 * @property busType One of [BUS_I2C] or [BUS_SPI].
 * @constructor Creates a BMP581 driver at the given I²C address and bus type.
 * @throws IOException on I²C error or wrong chip ID.
 */
open class Bmp581Minimal @JvmOverloads constructor(
    protected val connection: Connection,
    protected val busType: Int = BUS_I2C,
    private val addr: Int = 0x46,
) {

    init {
        init(addr)
    }

    /**
     * Read the pressure.
     *
     * @return Pressure in Pa.
     * @throws IOException on I²C error.
     */
    open fun pressure(): Double {
        waitForced()
        val buf = connection.writeRead(byteArrayOf(REG_PRESS_XLSB.toByte()), 3)
        return rawToPressure(((buf[2].toInt() and 0xFF) shl 16) or
            ((buf[1].toInt() and 0xFF) shl 8) or (buf[0].toInt() and 0xFF))
    }

    /**
     * Read the temperature.
     *
     * @return Temperature in °C.
     * @throws IOException on I²C error.
     */
    open fun temperature(): Double {
        waitForced()
        val buf = connection.writeRead(byteArrayOf(REG_TEMP_XLSB.toByte()), 3)
        return rawToTemperature(((buf[2].toInt() and 0xFF) shl 16) or
            ((buf[1].toInt() and 0xFF) shl 8) or (buf[0].toInt() and 0xFF))
    }

    /**
     * Read both pressure and temperature atomically in a single 6-byte burst.
     *
     * @return Pair of (pressure_Pa, temperature_C).
     * @throws IOException on I²C error.
     */
    open fun both(): Pair<Double, Double> {
        waitForced()
        val buf = connection.writeRead(byteArrayOf(REG_TEMP_XLSB.toByte()), 6)
        val rawT = ((buf[2].toInt() and 0xFF) shl 16) or
            ((buf[1].toInt() and 0xFF) shl 8) or (buf[0].toInt() and 0xFF)
        val rawP = ((buf[5].toInt() and 0xFF) shl 16) or
            ((buf[4].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        return rawToPressure(rawP) to rawToTemperature(rawT)
    }

    /** Runs the chip init sequence: verify ID, soft-reset, configure NORMAL 1 Hz. */
    protected fun init(addr: Int) {
        if (busType == BUS_SPI) {
            connection.write(byteArrayOf((REG_CHIP_ID or 0x80).toByte()))
            connection.read(1)
        }
        val id = connection.writeRead(byteArrayOf(REG_CHIP_ID.toByte()), 1)
        val chipId = id[0].toInt() and 0xFF
        if (chipId != CHIP_ID) {
            throw IOException(
                "BMP581 not found: expected 0x50, got 0x${Integer.toHexString(chipId)}")
        }
        for (i in 0 until 50) {
            val st = connection.writeRead(byteArrayOf(REG_STATUS.toByte()), 1)
            val s = st[0].toInt() and 0xFF
            if ((s and STATUS_NVM_RDY) != 0 && (s and STATUS_NVM_ERR) == 0) break
            try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        connection.writeRead(byteArrayOf(REG_INT_STATUS.toByte()), 1)
        try {
            connection.write(byteArrayOf(REG_CMD.toByte(), SOFT_RESET.toByte()))
        } catch (e: IOException) {
            // expected NACK on I²C during reset
        }
        try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        for (i in 0 until 50) {
            val st = connection.writeRead(byteArrayOf(REG_STATUS.toByte()), 1)
            val s = st[0].toInt() and 0xFF
            if ((s and STATUS_NVM_RDY) != 0 && (s and STATUS_NVM_ERR) == 0) break
            try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        connection.writeRead(byteArrayOf(REG_INT_STATUS.toByte()), 1)
        writeReg(REG_OSR_CONFIG, 0x40)
        writeReg(REG_ODR_CONFIG, 0x71)
    }

    /** Wait for drdy_data_reg if the chip is in FORCED mode. */
    protected fun waitForced() {
        if (pwrMode != MODE_FORCED) return
        for (i in 0 until 200) {
            val st = connection.writeRead(byteArrayOf(REG_INT_STATUS.toByte()), 1)
            if ((st[0].toInt() and INT_STATUS_DRDY) != 0) return
            try { Thread.sleep(5) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    /**
     * Write a single byte to a register, applying the SPI write-address mask
     * if this driver was constructed with [BUS_SPI].
     */
    protected fun writeReg(reg: Int, value: Int) {
        val addr = if (busType == BUS_SPI) (reg and 0x7F) else reg
        connection.write(byteArrayOf(addr.toByte(), (value and 0xFF).toByte()))
    }

    /** Decode a 24-bit raw pressure value to Pa (signed). */
    protected fun rawToPressure(raw: Int): Double {
        val signedRaw = if ((raw and 0x800000) != 0) (raw - 0x1000000) else raw
        return signedRaw / 64.0
    }

    /** Decode a 24-bit raw temperature value to °C (signed). */
    protected fun rawToTemperature(raw: Int): Double {
        val signedRaw = if ((raw and 0x800000) != 0) (raw - 0x1000000) else raw
        return signedRaw / 65536.0
    }

    companion object {
        /** Bus type: I²C (default). */
        const val BUS_I2C = 0
        /** Bus type: SPI — write addresses have bit 7 cleared; reads stay unmasked. */
        const val BUS_SPI = 1

        protected const val REG_CHIP_ID = 0x01
        protected const val REG_STATUS = 0x28
        protected const val REG_INT_STATUS = 0x27
        protected const val REG_TEMP_XLSB = 0x1D
        protected const val REG_PRESS_XLSB = 0x20
        protected const val REG_OSR_CONFIG = 0x36
        protected const val REG_ODR_CONFIG = 0x37
        protected const val REG_CMD = 0x7E

        protected const val CHIP_ID = 0x50
        protected const val SOFT_RESET = 0xB6
        protected const val STATUS_NVM_RDY = 0x02
        protected const val STATUS_NVM_ERR = 0x04
        protected const val INT_STATUS_DRDY = 0x01
        /** Shared with Full. */
        protected const val MODE_FORCED = 2

        protected var pwrMode: Int = 0x01
    }
}