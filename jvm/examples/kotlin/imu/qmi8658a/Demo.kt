///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.QMI8658AFull

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x6B").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val imu = QMI8658AFull(connection)                           // Create QMI8658A driver, (connection) → void

        // --- Identify the part and run the built-in checks ---
        // The revision, firmware version and USID identify the silicon; the
        // self-tests need the board lying still and the gyro calibration trims the
        // X/Y gains before real use.
        val info = imu.deviceInfo() // Read device info, () → DeviceInfo (revision, firmware, usid)
        println("revision 0x%02X firmware %d.%d.%d".format(info.revision, info.firmware[0], info.firmware[1], info.firmware[2]))
        val accelTest = imu.selfTestAccel() // Run accel self-test, () → SelfTestResult (passed, values m/s²)
        println("accel self-test: %s".format(if (accelTest.passed) "PASS" else "FAIL"))
        val gyroTest = imu.selfTestGyro() // Run gyro self-test, () → SelfTestResult (passed, values rad/s)
        println("gyro self-test: %s".format(if (gyroTest.passed) "PASS" else "FAIL"))
        val cal = imu.calibrateGyro() // Calibrate gyroscope, () → Calibration (success, codStatus)
        println("gyro calibration: %s".format(if (cal.success) "OK" else "FAILED"))

        // --- Configure ±4 g / ±512 dps at 112.1 Hz ---
        // ODR code 6 gives 112.1 Hz for both sensors; mode 3 filters keep the
        // noise bandwidth near 15 Hz.
        imu.configureAccel(1, 6) // Configure accelerometer, (fullScale, odr) → void
        imu.configureGyro(5, 6) // Configure gyroscope, (fullScale, odr) → void
        imu.configureFilters(true, 3, true, 3) // Configure low-pass filters, (accelFilter, accelMode, gyroFilter, gyroMode) → void

        // --- Step counting and tap detection ---
        // The pedometer defaults suit ~50 Hz, so the window parameters are scaled
        // for 112 Hz; the tap engine runs best above 200 Hz and is only a coarse
        // detector at this rate.
        imu.configurePedometer(112, 200.0, 100.0, 450, 45, 10, 0, 1) // Configure pedometer, (sampleCount, …) → void
        imu.enablePedometer(true) // Enable pedometer, (enabled) → void
        imu.configureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4) // Configure tap engine, (priority, …) → void
        imu.enableTap(true) // Enable tap engine, (enabled) → void

        // --- Stream into the FIFO ---
        // Stream mode with 32 samples and a watermark of 16 is drained once per
        // second; each sample is a 12-byte frame (accel XYZ then gyro XYZ).
        imu.configureFifo(2, 1, 16) // Configure FIFO, (mode, size, watermark) → void
        Thread.sleep(200)

        for (iteration in 0 until 600) {
        // --- Sample, tilt and step count ---
        if (imu.dataReady()) { // Check for new data, () → boolean
            val sm = imu.readAll() // Read one coherent sample, () → Sample (accel m/s², gyro rad/s, tempC °C)
            val roll = Math.toDegrees(Math.atan2(sm.accel[1], sm.accel[2]))
            val pitch = Math.toDegrees(Math.atan2(-sm.accel[0], Math.hypot(sm.accel[1], sm.accel[2])))
            val gmag = Math.sqrt(sm.gyro[0] * sm.gyro[0] + sm.gyro[1] * sm.gyro[1] + sm.gyro[2] * sm.gyro[2])
            val steps = imu.stepCount() // Read step count, () → int
            println("roll %.1f pitch %.1f |gyro| %.3f rad/s  %.1f C  steps %d".format(roll, pitch, gmag, sm.tempC, steps))
        }

        // --- Report taps ---
        // pollInterrupt() flags a tap without the INT pin wired.
        val status = imu.pollInterrupt() // Poll interrupt status, () → int
        if ((status and QMI8658AFull.SOURCE_TAP) != 0) {
            val tap = imu.tapStatus() // Read tap event, () → TapStatus (count, axis, negative)
            println("TAP %s on axis %d".format(if (tap.count == 2) "double" else "single", tap.axis))
        }

        // --- Drain the FIFO once per second ---
        if (iteration % 10 == 9) {
            val n = imu.fifoCount() // Read FIFO fill, () → int bytes
            val data = imu.readFifo(-1) // Drain the FIFO, (count) → byte[]
            println("FIFO: %d bytes (%d), %d frames".format(n, data.size, data.size / 12))
        }
        Thread.sleep(100)
        }
    }
}
