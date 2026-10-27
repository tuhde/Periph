///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;
import it.uhde.periph.chips.accelerometer.LSM303Source;

public class LSM303Test {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x19").replaceFirst("^0[xX]", ""), 16);

        try (var accelConnection = new I2CConnection(bus, addr);
             var magConnection = new I2CConnection(bus, 0x1E)) {
            var chip = new LSM303Full(accelConnection, magConnection);

            byte[] id = chip.readMagIdentity();                        // Read magnetometer identity, () → byte[]
            check("mag_identity", id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33);

            double[] a = chip.readAcceleration();                      // Read 3-axis acceleration, () → double[] g
            check("read_acceleration_finite", !Double.isNaN(a[0]) && !Double.isNaN(a[1]) && !Double.isNaN(a[2]));
            check("acceleration_plausible", Math.abs(a[0]) < 18 && Math.abs(a[1]) < 18 && Math.abs(a[2]) < 18);

            double[] m = chip.readMagnetic();                          // Read 3-axis magnetic field, () → double[] µT
            check("read_magnetic_finite", !Double.isNaN(m[0]) && !Double.isNaN(m[1]) && !Double.isNaN(m[2]));

            double heading = chip.heading(true);                       // Compute compass heading, (tiltCompensate) → double deg
            check("heading_range", heading >= 0 && heading <= 360);

            chip.setRange(4);                                          // Set accelerometer range, (rangeG) → void g
            check("read_after_set_range_4g", !Double.isNaN(chip.readAcceleration()[0]));

            int[] raw = chip.readAccelerationRaw();                    // Read raw acceleration counts, () → int[] LSB
            check("read_acceleration_raw_ints", raw[0] >= -2048 && raw[0] <= 2047);

            chip.setFifo(LSM303Full.FIFO_FIFO, 8, false);              // Configure FIFO, (mode, watermark, triggerInt2) → void
            check("fifo_level_in_range", chip.fifoLevel() <= 32);
            check("read_fifo", chip.readFifo(-1).length <= 32);
            chip.setFifo(LSM303Full.FIFO_BYPASS, 0, false);            // Configure FIFO, (mode, watermark, triggerInt2) → void

            chip.setHighPass(0, 0, false, false, false, false);        // Configure high-pass filter, (...) → void
            int ref = chip.resetHighPass();                            // Reset high-pass filter, () → int
            check("reset_high_pass_stable", chip.resetHighPass() == ref);

            chip.setMagRange(470);                                     // Set magnetometer range, (rangeUt) → void µT
            chip.setMagDataRate(30);                                   // Set magnetometer data rate, (rateHz) → void Hz
            check("read_after_set_mag_range", !Double.isNaN(chip.readMagnetic()[0]));

            chip.enableTemperature(true);                              // Enable temperature sensor, (enabled) → void
            double temp = chip.readTemperatureDelta();                 // Read relative temperature, () → double °C
            check("temperature_plausible", Math.abs(temp) < 100);

            chip.enableInterrupt(LSM303Source.SOURCE_DATA_READY, 1);   // Enable interrupt source, (source, pin) → void
            chip.pollInterrupt();                                      // Poll interrupt status, () → int
            chip.disableInterrupt(LSM303Source.SOURCE_DATA_READY, 1);  // Disable interrupt source, (source, pin) → void

            chip.reboot();                                             // Reboot accelerometer memory, () → void
            check("read_after_reboot", !Double.isNaN(chip.readAcceleration()[0]));
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
