///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.imu.Mpu6050Full

def transport = new I2CTransport(1, 0x68)
try {
    // --- Configure for motion logging with moderate dynamic range ---
    // ±4g captures typical tilting and handling forces without clipping;
    // ±500 dps covers fast rotations while retaining sub-degree resolution.
    def imu = new Mpu6050Full(transport)                           // Create MPU6050 driver, (transport, addr=0x68) → None
    imu.configureAccel(1)                                           // Configure accel range, (fullScale=0) → None
    imu.configureGyro(1)                                            // Configure gyro range, (fullScale=0) → None

    println "${'roll'.padRight(8)} ${'pitch'.padRight(8)} ${'|accel|'.padRight(10)} ${'|gyro|'.padRight(10)}"

    (0..<100).each { i ->
        // gate reads on dataReady so each sample reflects a fresh conversion
        while (!imu.dataReady()) {}                                 // Check data ready flag, () → boolean

        double[] a = imu.accel()                                   // Read 3-axis acceleration, () → double[] m/s²
        double[] g = imu.gyro()                                    // Read 3-axis angular rate, () → double[] rad/s

        // --- Compute tilt angles from the accelerometer gravity vector ---
        double roll  = Math.atan2(a[1], a[2]) * 180.0d / Math.PI
        double pitch = Math.atan2(-a[0], Math.sqrt(a[1]*a[1] + a[2]*a[2])) * 180.0d / Math.PI
        double accelMag = Math.sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2])
        double gyroMag  = Math.sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2])

        println "${String.format('%.1f', roll).padRight(8)} ${String.format('%.1f', pitch).padRight(8)} ${String.format('%.3f', accelMag).padRight(10)} ${String.format('%.3f', gyroMag).padRight(10)}"
        Thread.sleep(100)
    }
} finally {
    transport.close()
}
