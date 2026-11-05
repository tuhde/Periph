///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-kotlin:1.3.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.ICM20948Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val imu = ICM20948Full(connection)                           // Create ICM20948 driver, (connection) → void

        // --- Configure for motion and heading monitoring ---
        // ±4g / ±500dps give headroom for sharper motion; DLPFCFG 3 with a divider of
        // 10 keeps a ~100 Hz sample rate with a ~50 Hz noise bandwidth. The
        // magnetometer runs through the chip's own I²C master, so one connection
        // is enough.
        imu.configureAccel(1)                               // Configure accel range, (fullScale) → void
        imu.configureGyro(1)                                // Configure gyro range, (fullScale) → void
        imu.configureFilters(3, 3, true, true)              // Configure low-pass filters, (gyroDlpf, accelDlpf, gyroFilter, accelFilter) → void
        imu.configureSampleRate(10, 10)                     // Configure sample rate, (gyroDiv, accelDiv) → void
        imu.enableMag()                                     // Enable magnetometer, () → void
        val temp0 = imu.temperature()                       // Read die temperature, () → double °C
        println("ICM-20948 die temperature: %.1f C".format(temp0))

        // --- Arm wake-on-motion and route it to the interrupt status ---
        // A 200 mg threshold flags a bump; pollInterrupt() reports it as
        // SOURCE_WAKE_ON_MOTION without needing the INT pin wired.
        imu.setWakeOnMotion(200.0, true)                    // Arm wake-on-motion, (thresholdMg, enabled) → void
        imu.enableInterrupt(ICM20948Full.SOURCE_WAKE_ON_MOTION) // Enable interrupt source, (source) → void

        // --- Stream accelerometer + gyro into the FIFO ---
        // Stream mode overwrites the oldest data; once per second the FIFO is
        // drained and counted in 12-byte frames (accel X, Y, Z, gyro X, Y, Z).
        imu.enableFifo(true, true, false)                   // Enable FIFO sources, (accel, gyro, temp) → void
        imu.setFifoMode(false)                              // Select FIFO mode, (snapshot) → void

        println("roll     pitch    heading  |gyro|")

        for (iteration in 0 until 600) {                                // 600 x 100 ms = 60 s
            val s = imu.readAll()                           // Read one coherent sample, () → Sample (accel m/s², gyro rad/s, tempC °C)
            val m = imu.mag()                               // Read 3-axis magnetic field, () → DoubleArray µT

            // --- Compute roll, pitch and a tilt-compensated heading ---
            // The magnetometer frame is rotated 180° about X relative to the
            // inertial frame, so (mx, -my, -mz) maps it into the accel frame
            // before the tilt compensation rotates it into the horizontal plane.
            val roll = Math.atan2(s.accel[1], s.accel[2])
            val pitch = Math.atan2(-s.accel[0], Math.hypot(s.accel[1], s.accel[2]))
            val bx = m[0]; val by = -m[1]; val bz = -m[2]
            val xh = bx * Math.cos(pitch) + bz * Math.sin(pitch)
            val yh = bx * Math.sin(roll) * Math.sin(pitch) + by * Math.cos(roll) - bz * Math.sin(roll) * Math.cos(pitch)
            val heading = (Math.toDegrees(Math.atan2(-yh, xh)) + 360.0) % 360.0
            val gyroMag = Math.sqrt(s.gyro[0] * s.gyro[0] + s.gyro[1] * s.gyro[1] + s.gyro[2] * s.gyro[2])
            println("%-8.1f %-8.1f %-8.1f %-8.3f".format(Math.toDegrees(roll), Math.toDegrees(pitch), heading, gyroMag))

            val status = imu.pollInterrupt()                // Read interrupt status, () → int
            if ((status and ICM20948Full.SOURCE_WAKE_ON_MOTION) != 0) {
                println("MOTION")
            }

            if (iteration % 10 == 9) {
                val data = imu.readFifo(-1)                 // Read FIFO data, (count; <0 = all) → ByteArray
                println("FIFO: %d bytes, %d frames".format(data.size, (data.size / 12)))
            }
            Thread.sleep(100)
        }
    }
}
