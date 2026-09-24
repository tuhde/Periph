///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.MPU9250Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", "") as int

def conn = new I2CConnection(bus, addr)
def magConn = new I2CConnection(bus, 0x0C)  // AK8963, same bus, reached via I²C bypass
try {
    // --- Configure for tilt and heading estimation ---
    // ±4g / ±500dps trade sensitivity for headroom against sharper motion than
    // the ±2g / ±250dps defaults tolerate; 16-bit continuous magnetometer mode
    // keeps a fresh heading available on every poll.
    def imu = new MPU9250Full(conn, magConn)                // Create MPU9250 driver, (connection, magConnection) → void
    imu.configureAccel(1)                                    // Configure accel range, (fullScale=0) → void
    imu.configureGyro(1)                                     // Configure gyro range, (fullScale=0) → void
    imu.enableMag(16, 6)                                     // Initialize magnetometer, (bits=16, mode=6) → void

    println("roll     pitch    heading  |accel|  |gyro|")

    while (true) {
        // gate reads on dataReady so each sample reflects a fresh conversion
        while (!imu.dataReady()) {                           // Check data ready flag, () → boolean
        }

        def a = imu.accel()                                 // Read 3-axis acceleration, () → double[] m/s²
        def g = imu.gyro()                                  // Read 3-axis angular rate, () → double[] rad/s
        def m = imu.mag()                                   // Read 3-axis magnetic field, () → double[] µT

        // --- Compute tilt angles from the accelerometer gravity vector ---
        // roll and pitch are reliable when the device is quasi-static;
        // gyro magnitude indicates how fast the board is being rotated.
        def roll  = Math.atan2(a[1], a[2]) * 180.0 / Math.PI
        def pitch = Math.atan2(-a[0], Math.sqrt(a[1]*a[1] + a[2]*a[2])) * 180.0 / Math.PI

        // --- Compute magnetic heading (simplified, no tilt compensation) ---
        // Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
        def heading = Math.atan2(m[1], m[0]) * 180.0 / Math.PI

        def accelMag = Math.sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2])
        def gyroMag  = Math.sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2])

        printf("%.1f      %.1f      %.1f      %.3f    %.3f%n", roll, pitch, heading, accelMag, gyroMag)
        Thread.sleep(100)
    }
} finally {
    conn.close()
    magConn.close()
}