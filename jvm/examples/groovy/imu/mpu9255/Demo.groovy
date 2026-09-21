///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.imu.Mpu9255Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = System.getenv().getOrDefault("I2C_ADDR", "0x68").replaceFirst("^0[xX]", "") as int

def conn = new I2CConnection(bus, addr)
def magConn = new I2CConnection(bus, 0x0C)  // AK8963, same bus, reached via I²C bypass
try {
    // --- Configure for motion-triggered wake logger ---
    // 64 mg threshold and 31.25 Hz wake-up rate balance sensitivity against spurious
    // wake-ups from vibration; once motion fires, the full 6-axis sensor suite
    // (gyro + mag at 100 Hz) is re-enabled to capture a 5-second tilt/heading burst.
    def imu = new Mpu9255Full(conn, magConn)                // Create MPU9255 driver, (connection, magConnection) → void
    imu.configureWakeOnMotion(64, 31.25f)                    // Configure wake-on-motion, (thresholdMg=64, odrHz=31.25) → void

    def lastHeartbeat = System.currentTimeMillis()
    while (true) {
        // --- Idle phase: motion poll at ~5 Hz, "sleeping…" heartbeat at ~1 Hz ---
        // configure_wake_on_motion already disabled the gyro and put the chip
        // in CYCLE=1 duty-cycled mode; polling motion_detected() reflects that
        // state without forcing any further register writes.
        while (!imu.motionDetected()) {                       // Check motion detected, () → boolean
            def now = System.currentTimeMillis()
            if (now - lastHeartbeat >= 1000) {
                println("sleeping...")
                lastHeartbeat = now
            }
            Thread.sleep(200)
        }

        // --- Wake phase: re-arm the full 6-axis + mag stack ---
        // setSleep(false) clears CYCLE; configureGyro re-enables the gyro axes.
        imu.setSleep(false)                                    // Wake from sleep, (sleep=true) → void
        imu.configureGyro(1)                                   // Configure gyro range, (fullScale=0) → void
        imu.configureAccel(1)                                  // Configure accel range, (fullScale=0) → void
        imu.enableMag(16, 6)                                   // Initialize magnetometer, (bits=16, mode=6) → void

        // --- Capture a 5-second tilt/heading burst at ~10 Hz ---
        // Roll/pitch from gravity (quasi-static) + heading from mag (no tilt comp).
        println("--- motion detected ---")
        def end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            while (!imu.dataReady()) {                         // Check data ready flag, () → boolean
            }

            def a = imu.accel()                              // Read 3-axis acceleration, () → double[] m/s²
            def g = imu.gyro()                               // Read 3-axis angular rate, () → double[] rad/s
            def m = imu.mag()                                // Read 3-axis magnetic field, () → double[] µT

            def roll  = Math.atan2(a[1], a[2]) * 180.0 / Math.PI
            def pitch = Math.atan2(-a[0], Math.sqrt(a[1]*a[1] + a[2]*a[2])) * 180.0 / Math.PI
            def heading = Math.atan2(m[1], m[0]) * 180.0 / Math.PI
            def gyroMag = Math.sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2])

            printf("%.1f      %.1f      %.1f      |g|=%.2f%n", roll, pitch, heading, gyroMag)
            Thread.sleep(100)
        }

        // --- Return to low-power wake-on-motion mode ---
        imu.configureWakeOnMotion(64, 31.25f)                // Configure wake-on-motion, (thresholdMg=64, odrHz=31.25) → void
        lastHeartbeat = System.currentTimeMillis()
    }
} finally {
    conn.close()
    magConn.close()
}