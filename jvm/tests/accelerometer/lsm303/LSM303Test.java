///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Minimal;
import it.uhde.periph.chips.accelerometer.LSM303Full;

import java.io.IOException;

public class LSM303Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        // The module presents two I²C slaves on the same bus: accelerometer 0x19
        // and magnetometer 0x1E — both connections are required.
        try (var accelConn = new I2CConnection(bus, 0x19);
             var magConn   = new I2CConnection(bus, 0x1E)) {

            // --- Identity + construction ---
            LSM303Minimal chip;
            try {
                chip = new LSM303Minimal(accelConn, magConn);
                checkTrue("mag_identity_and_construct", true);
            } catch (IOException e) {
                System.out.println("FAIL mag_identity_and_construct: " + e.getMessage());
                failed++;
                System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
                System.exit(1);
                return;
            }

            // --- Minimal read loop ---
            double[] a = chip.readAcceleration();
            double aMag = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
            checkTrue("accel_3_components", a.length == 3);
            checkTrue("accel_magnitude_near_1g", Double.isFinite(aMag) && aMag >= 0.3 && aMag <= 1.7);

            double[] m = chip.readMagnetic();
            double mMag = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
            checkTrue("mag_3_components", m.length == 3);
            checkTrue("mag_magnitude_plausible_ut", Double.isFinite(mMag) && mMag >= 0.0 && mMag <= 300.0);

            // --- Full API checks ---
            var full = new LSM303Full(accelConn, magConn);
            checkTrue("construct_full", true);

            byte[] ident = full.readMagIdentity();
            checkTrue("read_mag_identity", ident.length == 3
                    && (ident[0] & 0xFF) == 0x48 && (ident[1] & 0xFF) == 0x34 && (ident[2] & 0xFF) == 0x33);

            int[] raw = full.readAccelerationRaw();
            checkTrue("read_raw_3_counts", raw.length == 3 && raw[0] >= -32768 && raw[0] <= 32767);

            double hdgFlat = full.heading(false);
            checkTrue("heading_flat_range", hdgFlat >= 0.0 && hdgFlat < 360.0);
            double hdgTilt = full.heading(true);
            checkTrue("heading_tilt_compensated_range", hdgTilt >= 0.0 && hdgTilt < 360.0);

            double temp = full.readTemperatureDelta();
            checkTrue("temperature_finite", Double.isFinite(temp));

            full.setFifo(2, 16, false);
            int level = full.fifoLevel();
            checkTrue("fifo_level_range", level >= 0 && level <= 32);
            full.setFifo(0, 0, false);                       // only bypass empties the buffer

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
