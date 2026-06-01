///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.imu.Mpu6050Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x68)) {
            // --- Configure for motion logging with moderate dynamic range ---
            // ±4g captures typical tilting and handling forces without clipping;
            // ±500 dps covers fast rotations while retaining sub-degree resolution.
            var imu = new Mpu6050Full(transport);                  // Create MPU6050 driver, (transport, addr=0x68) → None
            imu.configureAccel(1);                                  // Configure accel range, (fullScale=0) → None
            imu.configureGyro(1);                                   // Configure gyro range, (fullScale=0) → None

            System.out.printf("%-8s %-8s %-10s %-10s%n", "roll", "pitch", "|accel|", "|gyro|");

            for (int i = 0; i < 100; i++) {
                // gate reads on dataReady so each sample reflects a fresh conversion
                while (!imu.dataReady()) {}                         // Check data ready flag, () → bool

                double[] a = imu.accel();                          // Read 3-axis acceleration, () → double[] m/s²
                double[] g = imu.gyro();                           // Read 3-axis angular rate, () → double[] rad/s

                // --- Compute tilt angles from the accelerometer gravity vector ---
                double roll  = Math.atan2(a[1], a[2]) * 180.0 / Math.PI;
                double pitch = Math.atan2(-a[0], Math.sqrt(a[1]*a[1] + a[2]*a[2])) * 180.0 / Math.PI;
                double accelMag = Math.sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2]);
                double gyroMag  = Math.sqrt(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]);

                System.out.printf("%-8.1f %-8.1f %-10.3f %-10.3f%n", roll, pitch, accelMag, gyroMag);
                Thread.sleep(100);
            }
        }
    }
}
