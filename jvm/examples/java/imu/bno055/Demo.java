///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.imu.BNO055Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x29").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var imu = new BNO055Full(connection);                                           // Create BNO055 driver, (connection) → void

            // --- Identify the part ---
            int[] rev = imu.revisions();                                                    // Read revisions, () → int[]
            System.out.printf("chip 0x%02X, SW %d.%d, bootloader %d%n", rev[0], rev[4] >> 8, rev[4] & 0xFF, rev[5]);
            System.out.printf("unique id %s%n", java.util.HexFormat.of().formatHex(imu.uniqueId()));
            // --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
            while (true) {
                double[] eul = imu.euler();                                                 // Read fused Euler angles, () → double[] rad
                double[] q = imu.quaternion();                                              // Read orientation quaternion, () → double[]
                double[] lin = imu.linearAcceleration();                                    // Read gravity-free acceleration, () → double[] m/s²
                double[] grav = imu.gravity();                                              // Read gravity vector, () → double[] m/s²
                int[] cal = imu.calibrationStatus();                                        // Read calibration status, () → int[]
                System.out.printf("euler %s quat %s lin %s grav %s cal %s%n", java.util.Arrays.toString(eul), java.util.Arrays.toString(q), java.util.Arrays.toString(lin), java.util.Arrays.toString(grav), java.util.Arrays.toString(cal));
                if (imu.isCalibrated()) break;
                Thread.sleep(100);
            }
            // --- Save the calibration, relative mode for 5 s, restore it ---
            byte[] blob = imu.getCalibration();                                             // Read offsets and radii, () → byte[22]
            System.out.printf("calibration %s%n", java.util.HexFormat.of().formatHex(blob));
            imu.setMode(BNO055Full.MODE_IMU);                                               // Select operation mode, (mode) → void
            System.out.printf("mode %d%n", imu.getMode());
            Thread.sleep(5000);
            imu.setMode(BNO055Full.MODE_NDOF);                                              // Select operation mode, (mode) → void
            imu.setCalibration(blob);                                                       // Restore offsets and radii, (data) → void
            // --- Self-test, temperature and configuration ---
            System.out.printf("self-test %s%n", java.util.Arrays.toString(imu.selfTest()));
            System.out.printf("temperature %d C%n", imu.temperature());
            imu.setTemperatureSource("gyroscope");                                          // Select temperature sensor, (source) → void
            imu.setAxisRemap(0x24, 0x00);                                                   // Remap axes, (config, sign) → void
            imu.setPowerMode("normal");                                                     // Select power mode, (mode) → void
            imu.useExternalCrystal(false);                                                  // Select clock source, (on) → void
            // --- Raw sensors in AMG mode ---
            imu.setMode(BNO055Full.MODE_AMG);                                               // Select operation mode, (mode) → void
            double[] acc = imu.acceleration();                                              // Read raw accelerometer, () → double[] m/s²
            double[] mag = imu.magneticField();                                             // Read raw magnetometer, () → double[] µT
            double[] gyr = imu.angularRate();                                               // Read raw gyroscope, () → double[] rad/s
            System.out.printf("acc %s mag %s gyr %s%n", java.util.Arrays.toString(acc), java.util.Arrays.toString(mag), java.util.Arrays.toString(gyr));
            // --- Interrupts: accelerometer/fusion data ready ---
            imu.setMode(BNO055Full.MODE_NDOF);                                              // Select operation mode, (mode) → void
            imu.enableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY);                            // Enable interrupt source, (source) → void
            imu.onInterrupt(s -> System.out.printf("INT 0x%02X%n", s));                     // Subscribe to interrupts, (callback) → void
            Thread.sleep(500);
            imu.offInterrupt();                                                             // Unsubscribe, () → void
            imu.disableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY);                           // Disable interrupt source, (source) → void
            System.out.printf("interrupt status 0x%02X%n", imu.pollInterrupt());
            System.out.printf("system status %s%n", java.util.Arrays.toString(imu.systemStatus()));
            imu.reset();                                                                    // Reset the chip, () → void
        }
    }
}
