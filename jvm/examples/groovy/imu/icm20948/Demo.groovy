///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-groovy:1.3.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.ICM20948Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", "") as int

def conn = new I2CConnection(bus, addr)
try {
    def imu = new ICM20948Full(conn)                           // Create ICM20948 driver, (connection) → void

    // --- Configure for motion and heading monitoring ---
    // ±4g / ±500dps give headroom for sharper motion; DLPFCFG 3 with a divider of
    // 10 keeps a ~100 Hz sample rate with a ~50 Hz noise bandwidth. The
    // magnetometer runs through the chip's own I²C master, so one connection
    // is enough.
    imu.configureAccel(1)                                   // Configure accel range, (fullScale) → void
    imu.configureGyro(1)                                    // Configure gyro range, (fullScale) → void
    imu.configureFilters(3, 3, true, true)                  // Configure low-pass filters, (gyroDlpf, accelDlpf, gyroFilter, accelFilter) → void
    imu.configureSampleRate(10, 10)                         // Configure sample rate, (gyroDiv, accelDiv) → void
    imu.enableMag()                                         // Enable magnetometer, () → void
    def temp0 = imu.temperature()                           // Read die temperature, () → double °C
    printf("ICM-20948 die temperature: %.1f C%n", temp0)

    // --- Arm wake-on-motion and route it to the interrupt status ---
    // A 200 mg threshold flags a bump; pollInterrupt() reports it as
    // SOURCE_WAKE_ON_MOTION without needing the INT pin wired.
    imu.setWakeOnMotion(200.0, true)                        // Arm wake-on-motion, (thresholdMg, enabled) → void
    imu.enableInterrupt(ICM20948Full.SOURCE_WAKE_ON_MOTION) // Enable interrupt source, (source) → void

    // --- Stream accelerometer + gyro into the FIFO ---
    // Stream mode overwrites the oldest data; once per second the FIFO is
    // drained and counted in 12-byte frames (accel X, Y, Z, gyro X, Y, Z).
    imu.enableFifo(true, true, false)                       // Enable FIFO sources, (accel, gyro, temp) → void
    imu.setFifoMode(false)                                  // Select FIFO mode, (snapshot) → void

    println "roll     pitch    heading  |gyro|"

    for (int iteration = 0; iteration < 600; iteration++) {         // 600 x 100 ms = 60 s
        def s = imu.readAll()                               // Read one coherent sample, () → Sample (accel m/s², gyro rad/s, tempC °C)
        def m = imu.mag()                                   // Read 3-axis magnetic field, () → double[] µT

        // --- Compute roll, pitch and a tilt-compensated heading ---
        // The magnetometer frame is rotated 180° about X relative to the
        // inertial frame, so (mx, -my, -mz) maps it into the accel frame
        // before the tilt compensation rotates it into the horizontal plane.
        double roll = Math.atan2(s.accel[1], s.accel[2])
        double pitch = Math.atan2(-s.accel[0], Math.hypot(s.accel[1], s.accel[2]))
        double bx = m[0], by = -m[1], bz = -m[2]
        double xh = bx * Math.cos(pitch) + bz * Math.sin(pitch)
        double yh = bx * Math.sin(roll) * Math.sin(pitch) + by * Math.cos(roll) - bz * Math.sin(roll) * Math.cos(pitch)
        double heading = (Math.toDegrees(Math.atan2(-yh, xh)) + 360.0) % 360.0
        double gyroMag = Math.sqrt(s.gyro[0] * s.gyro[0] + s.gyro[1] * s.gyro[1] + s.gyro[2] * s.gyro[2])
        printf("%-8.1f %-8.1f %-8.1f %-8.3f%n", Math.toDegrees(roll), Math.toDegrees(pitch), heading, gyroMag)

        def status = imu.pollInterrupt()                    // Read interrupt status, () → int
        if ((status & ICM20948Full.SOURCE_WAKE_ON_MOTION) != 0) {
            println "MOTION"
        }

        if (iteration % 10 == 9) {
            def data = imu.readFifo(-1)                     // Read FIFO data, (count; <0 = all) → byte[]
            printf("FIFO: %d bytes, %d frames%n", data.length, (data.length / 12))
        }
        Thread.sleep(100)
    }
} finally {
    conn.close()
}
