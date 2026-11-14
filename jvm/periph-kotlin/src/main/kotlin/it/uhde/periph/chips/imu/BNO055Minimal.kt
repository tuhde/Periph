package it.uhde.periph.chips.imu

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * BNO055 — 9-axis absolute-orientation sensor (Bosch Sensortec), minimal driver.
 *
 * The constructor waits for the chip to boot (up to 650 ms), checks `CHIP_ID` (0xA0), selects
 * normal power mode and `UNIT_SEL` = 0x86 (Android orientation, Euler angles in radians, angular
 * rate in rad/s, acceleration in m/s², temperature in °C), then enters NDOF fusion mode (100 Hz
 * absolute orientation, internal oscillator).
 *
 * I²C address 0x29 (COM3 high, datasheet default) or 0x28 (COM3 low). The chip stretches the I²C
 * clock; the host bus must support clock stretching.
 *
 * @throws IOException on bus error or if `CHIP_ID` is not 0xA0 within 650 ms
 */
open class BNO055Minimal(
    protected val connection: RegisterConnection
) {
    companion object {
        const val REG_CHIP_ID         = 0x00
        const val REG_PAGE_ID         = 0x07
        const val REG_ACC_DATA        = 0x08
        const val REG_MAG_DATA        = 0x0E
        const val REG_GYR_DATA        = 0x14
        const val REG_EUL_DATA        = 0x1A
        const val REG_QUA_DATA        = 0x20
        const val REG_LIA_DATA        = 0x28
        const val REG_GRV_DATA        = 0x2E
        const val REG_TEMP            = 0x34
        const val REG_CALIB_STAT      = 0x35
        const val REG_ST_RESULT       = 0x36
        const val REG_INT_STA         = 0x37
        const val REG_SYS_STATUS      = 0x39
        const val REG_UNIT_SEL        = 0x3B
        const val REG_OPR_MODE        = 0x3D
        const val REG_PWR_MODE        = 0x3E
        const val REG_SYS_TRIGGER     = 0x3F
        const val REG_TEMP_SOURCE     = 0x40
        const val REG_AXIS_MAP_CONFIG = 0x41
        const val REG_AXIS_MAP_SIGN   = 0x42
        const val REG_ACC_OFFSET      = 0x55
        const val REG_INT_MSK         = 0x0F  // page 1
        const val REG_INT_EN          = 0x10  // page 1
        const val REG_UNIQUE_ID       = 0x50  // page 1

        const val CHIP_ID_VALUE = 0xA0
        const val UNIT_SEL_SI   = 0x86
        const val MODE_CONFIG_V = 0x00
        const val MODE_NDOF_V   = 0x0C
    }

    protected var curMode = MODE_NDOF_V

    init {
        initialize()
    }

    /** Boot wait, identity check and NDOF entry (also used by `reset()`). */
    protected fun initialize() {
        val deadline = System.currentTimeMillis() + 650
        var id = -1
        while (true) {
            var last: IOException? = null
            try {
                id = r1(REG_CHIP_ID)
                if (id == CHIP_ID_VALUE) break
            } catch (e: IOException) {
                last = e                           // the chip does not ACK while booting
            }
            if (System.currentTimeMillis() >= deadline) {
                if (last != null) throw last
                throw IOException("BNO055 CHIP_ID: expected 0xA0, got 0x${id.toString(16)}")
            }
            sleepMs(10)
        }
        w(REG_PWR_MODE, 0x00)
        w(REG_PAGE_ID, 0x00)
        w(REG_UNIT_SEL, UNIT_SEL_SI)
        w(REG_OPR_MODE, MODE_NDOF_V)
        curMode = MODE_NDOF_V
        sleepMs(7)
    }

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun w(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    protected fun r1(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    /** Signed little-endian 16-bit value at [offset]. */
    protected fun le16(b: ByteArray, offset: Int): Int =
        (((b[offset + 1].toInt() and 0xFF) shl 8) or (b[offset].toInt() and 0xFF)).toShort().toInt()

    protected fun vector(reg: Int, scale: Double): DoubleArray {
        val b = connection.read(reg, 6)
        return doubleArrayOf(le16(b, 0) / scale, le16(b, 2) / scale, le16(b, 4) / scale)
    }

    /**
     * Read the fused Euler angles (one burst of 0x1A–0x1F, raw / 900).
     *
     * @return {heading, roll, pitch} in rad
     */
    fun euler(): DoubleArray = vector(REG_EUL_DATA, 900.0)
}
