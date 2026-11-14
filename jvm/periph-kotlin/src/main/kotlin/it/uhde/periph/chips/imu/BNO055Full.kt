package it.uhde.periph.chips.imu

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * BNO055 — 9-axis absolute-orientation sensor (Bosch Sensortec), full driver extending [BNO055Minimal].
 *
 * Adds the quaternion, linear acceleration, gravity, raw sensor data, calibration read/write,
 * operation and power modes, axis remap, self-test, identification and the page-1 interrupts.
 */
open class BNO055Full(connection: RegisterConnection) : BNO055Minimal(connection) {
    companion object {
        /** Operation mode: CONFIGMODE. */
        const val MODE_CONFIG = 0x0
        /** Operation mode: raw accelerometer only. */
        const val MODE_ACCONLY = 0x1
        /** Operation mode: raw magnetometer only. */
        const val MODE_MAGONLY = 0x2
        /** Operation mode: raw gyroscope only. */
        const val MODE_GYROONLY = 0x3
        /** Operation mode: raw accelerometer and magnetometer. */
        const val MODE_ACCMAG = 0x4
        /** Operation mode: raw accelerometer and gyroscope. */
        const val MODE_ACCGYRO = 0x5
        /** Operation mode: raw magnetometer and gyroscope. */
        const val MODE_MAGGYRO = 0x6
        /** Operation mode: all three raw sensors. */
        const val MODE_AMG = 0x7
        /** Operation mode: relative-orientation fusion (100 Hz). */
        const val MODE_IMU = 0x8
        /** Operation mode: absolute heading fusion (20 Hz). */
        const val MODE_COMPASS = 0x9
        /** Operation mode: relative fusion with magnetometer for gyroscope (50 Hz). */
        const val MODE_M4G = 0xA
        /** Operation mode: 9-DOF fusion, fast magnetometer calibration off. */
        const val MODE_NDOF_FMC_OFF = 0xB
        /** Operation mode: 9-DOF absolute orientation (100 Hz, default). */
        const val MODE_NDOF = 0xC

        /** Interrupt source: accelerometer / fusion data ready. */
        const val SOURCE_ACC_BSX_DRDY = 0x01
        /** Interrupt source: magnetometer data ready. */
        const val SOURCE_MAG_DRDY = 0x02
        /** Interrupt source: gyroscope any motion. */
        const val SOURCE_GYRO_AM = 0x04
        /** Interrupt source: gyroscope high rate. */
        const val SOURCE_GYR_HIGH_RATE = 0x08
        /** Interrupt source: gyroscope data ready. */
        const val SOURCE_GYR_DRDY = 0x10
        /** Interrupt source: accelerometer high-g. */
        const val SOURCE_ACC_HIGH_G = 0x20
        /** Interrupt source: accelerometer any motion. */
        const val SOURCE_ACC_AM = 0x40
        /** Interrupt source: accelerometer no/slow motion. */
        const val SOURCE_ACC_NM = 0x80
    }

    private var clkSel = 0x00
    private var callback: IntConsumer? = null
    private var intPin: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun enterConfig(): Int {
        val prev = curMode
        w(REG_OPR_MODE, MODE_CONFIG_V)
        sleepMs(19)
        return prev
    }

    private fun leaveConfig(prev: Int) {
        if (prev != MODE_CONFIG_V) {
            w(REG_OPR_MODE, prev)
            sleepMs(7)
        }
    }

    private inline fun <T> inConfig(block: () -> T): T {
        val prev = enterConfig()
        try {
            return block()
        } finally {
            leaveConfig(prev)
        }
    }

    /** Select the operation mode through CONFIGMODE (19 ms + 7 ms waits); [newMode] 0 … 12. */
    fun setMode(newMode: Int) {
        require(newMode in 0..12) { "mode must be 0..12" }
        w(REG_OPR_MODE, MODE_CONFIG_V)
        sleepMs(19)
        if (newMode != MODE_CONFIG_V) {
            w(REG_OPR_MODE, newMode)
            sleepMs(7)
        }
        curMode = newMode
    }

    /** Read `OPR_MODE`. @return current operation mode, 0–12 */
    fun getMode(): Int = r1(REG_OPR_MODE) and 0x0F

    /** Read the fused orientation quaternion (raw / 16384). @return {w, x, y, z} */
    fun quaternion(): DoubleArray {
        val b = connection.read(REG_QUA_DATA, 8)
        return doubleArrayOf(le16(b, 0) / 16384.0, le16(b, 2) / 16384.0, le16(b, 4) / 16384.0, le16(b, 6) / 16384.0)
    }

    /** Read the gravity-free linear acceleration (raw / 100). @return {x, y, z} in m/s² */
    fun linearAcceleration(): DoubleArray = vector(REG_LIA_DATA, 100.0)

    /** Read the gravity vector (raw / 100). @return {x, y, z} in m/s² */
    fun gravity(): DoubleArray = vector(REG_GRV_DATA, 100.0)

    /** Read the raw accelerometer (raw / 100). @return {x, y, z} in m/s² */
    fun acceleration(): DoubleArray = vector(REG_ACC_DATA, 100.0)

    /** Read the raw magnetometer (raw / 16). @return {x, y, z} in µT */
    fun magneticField(): DoubleArray = vector(REG_MAG_DATA, 16.0)

    /** Read the raw gyroscope (raw / 900). @return {x, y, z} in rad/s */
    fun angularRate(): DoubleArray = vector(REG_GYR_DATA, 900.0)

    /** Read the temperature (signed 8 bit, 1 LSB = 1 °C). @return temperature in °C */
    fun temperature(): Int = r1(REG_TEMP).toByte().toInt()

    /** Read `CALIB_STAT`. @return {sys, gyr, acc, mag}, each 0 … 3 */
    fun calibrationStatus(): IntArray {
        val v = r1(REG_CALIB_STAT)
        return intArrayOf((v shr 6) and 3, (v shr 4) and 3, (v shr 2) and 3, v and 3)
    }

    /** Check whether sys, gyr, acc and mag are all 3. */
    fun isCalibrated(): Boolean = r1(REG_CALIB_STAT) == 0xFF

    /** Read the 22 offset/radius bytes 0x55–0x6A in CONFIGMODE (previous mode restored). */
    fun getCalibration(): ByteArray = inConfig { connection.read(REG_ACC_OFFSET, 22) }

    /** Write the 22 offset/radius bytes in CONFIGMODE (previous mode restored). */
    fun setCalibration(data: ByteArray) {
        require(data.size == 22) { "calibration data must be 22 bytes" }
        inConfig { for (i in 0 until 22) w(REG_ACC_OFFSET + i, data[i].toInt() and 0xFF) }
    }

    /** Remap the sensor axes: [config] `AXIS_MAP_CONFIG` (default 0x24), [sign] `AXIS_MAP_SIGN` (default 0x00). */
    fun setAxisRemap(config: Int, sign: Int) {
        inConfig {
            w(REG_AXIS_MAP_CONFIG, config)
            w(REG_AXIS_MAP_SIGN, sign)
        }
    }

    /** Select the power mode: "normal", "low_power" or "suspend". */
    fun setPowerMode(powerMode: String) {
        val code = when (powerMode) {
            "normal" -> 0
            "low_power" -> 1
            "suspend" -> 2
            else -> throw IllegalArgumentException("power mode must be 'normal', 'low_power' or 'suspend'")
        }
        inConfig { w(REG_PWR_MODE, code) }
    }

    /** Select the temperature sensor: "accelerometer" or "gyroscope". */
    fun setTemperatureSource(source: String) {
        val code = when (source) {
            "accelerometer" -> 0
            "gyroscope" -> 1
            else -> throw IllegalArgumentException("source must be 'accelerometer' or 'gyroscope'")
        }
        inConfig { w(REG_TEMP_SOURCE, code) }
    }

    /** Select the external 32.768 kHz crystal (only if fitted) or the internal oscillator. */
    fun useExternalCrystal(on: Boolean) {
        inConfig {
            clkSel = if (on) 0x80 else 0x00
            w(REG_SYS_TRIGGER, clkSel)
        }
    }

    /** Run the power-on self-test (waits 500 ms). @return {acc, mag, gyr, mcu}, true = passed */
    fun selfTest(): BooleanArray {
        val v = inConfig {
            w(REG_SYS_TRIGGER, clkSel or 0x01)
            sleepMs(500)
            r1(REG_ST_RESULT)
        }
        return booleanArrayOf((v and 1) != 0, (v and 2) != 0, (v and 4) != 0, (v and 8) != 0)
    }

    /** Read `SYS_STATUS` and `SYS_ERR`. @return {status, error}; status 5 = fusion running */
    fun systemStatus(): IntArray {
        val b = connection.read(REG_SYS_STATUS, 2)
        return intArrayOf(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF)
    }

    /** Reset the chip (`RST_SYS`), wait 650 ms and re-initialise into NDOF. */
    fun reset() {
        w(REG_SYS_TRIGGER, clkSel or 0x20)
        sleepMs(650)
        clkSel = 0
        initialize()
    }

    /** Read the identification registers. @return {chipId, accId, magId, gyrId, swRevision, bootloader} */
    fun revisions(): IntArray {
        val b = connection.read(REG_CHIP_ID, 7)
        fun u(i: Int) = b[i].toInt() and 0xFF
        return intArrayOf(u(0), u(1), u(2), u(3), u(4) or (u(5) shl 8), u(6))
    }

    /** Read the 16-byte unique ID from page 1 (page 0 restored). */
    fun uniqueId(): ByteArray {
        w(REG_PAGE_ID, 0x01)
        try {
            return connection.read(REG_UNIQUE_ID, 16)
        } finally {
            w(REG_PAGE_ID, 0x00)
        }
    }

    private fun updateIntReg(source: Int, enable: Boolean) {
        w(REG_PAGE_ID, 0x01)
        try {
            for (reg in intArrayOf(REG_INT_EN, REG_INT_MSK)) {
                var v = r1(reg)
                v = if (enable) v or source else v and source.inv() and 0xFF
                w(reg, v)
            }
        } finally {
            w(REG_PAGE_ID, 0x00)
        }
    }

    /** Enable interrupt source bits in `INT_EN` and `INT_MSK` (page 1). */
    fun enableInterrupt(source: Int) = updateIntReg(source, true)

    /** Disable interrupt source bits in `INT_EN` and `INT_MSK`. */
    fun disableInterrupt(source: Int) = updateIntReg(source, false)

    /** Read `INT_STA` (cleared on read). @return `SOURCE_*` bits */
    fun pollInterrupt(): Int = r1(REG_INT_STA)

    /**
     * Subscribe to rising edges on the connection's INT pin (a 100 ms polling thread when none is
     * attached). The callback receives the [pollInterrupt] status for a non-zero status.
     */
    fun onInterrupt(callback: IntConsumer) {
        offInterrupt()
        this.callback = callback
        val pin = connection.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.RISING)
        } else {
            polling = true
            val t = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "bno055-poll")
            t.isDaemon = true
            pollThread = t
            t.start()
        }
    }

    /** Unsubscribe and stop the interrupt delivery. */
    fun offInterrupt() {
        callback = null
        intPin?.offEdge(edgeHandler)
        intPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            val cb = callback
            if (status != 0 && cb != null) cb.accept(status)
        } catch (ignored: IOException) {
            // bus error; wait for the next edge/tick
        }
    }
}
