///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;
import it.uhde.periph.chips.accelerometer.LSM303Minimal;

public class LSM303Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    static boolean finite(double... v) {
        for (double d : v) if (!Double.isFinite(d)) return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var accelConn = new I2CConnection(bus, 0x19);
             var magConn   = new I2CConnection(bus, 0x1E)) {

            var minimal = new LSM303Minimal(accelConn, magConn);
            checkTrue(true, "construct_minimal");

            double[] a = minimal.readAcceleration();
            checkTrue(finite(a[0], a[1], a[2]), "accel_returns_floats");
            double mag = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
            checkTrue(mag >= 0.5 && mag <= 1.5, "accel_magnitude_near_1g");

            double[] m = minimal.readMagnetic();
            checkTrue(finite(m[0], m[1], m[2]), "mag_returns_floats");

            var full = new LSM303Full(accelConn, magConn);
            checkTrue(true, "construct_full");

            full.setRange(4);
            a = full.readAcceleration();
            checkTrue(finite(a[0], a[1], a[2]), "accel_after_set_range_4g");

            full.setDataRate(50);
            Thread.sleep(100);
            checkTrue(full.newDataAvailable(), "new_data_available_at_50hz");

            int[] raw = full.readAccelerationRaw();
            checkTrue(raw.length == 3, "raw_counts_length_3");

            byte[] id = full.readMagIdentity();
            checkTrue(id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33, "mag_identity_H43");

            full.setMagRange(130);
            full.setMagDataRate(15);
            full.setMagMode(0);
            int[] magRaw = full.readMagneticRaw();
            checkTrue(magRaw.length == 3, "mag_raw_counts_length_3");

            double h = full.heading(false);
            checkTrue(finite(h) && h >= 0.0 && h < 360.0, "heading_flat_in_range");
            double ht = full.heading(true);
            checkTrue(finite(ht) && ht >= 0.0 && ht < 360.0, "heading_tilt_in_range");

            full.enableTemperature(true);
            double dT = full.readTemperatureDelta();
            checkTrue(finite(dT), "temperature_delta_finite");

            full.enableInterrupt(LSM303Full.SOURCE_DATA_READY);
            int status = full.pollInterrupt();
            checkTrue((status & ~0xFF) == 0, "poll_interrupt_within_status_bits");
            full.disableInterrupt(LSM303Full.SOURCE_DATA_READY);

            full.setFifo(0);
            checkTrue(full.fifoLevel() == 0, "fifo_bypass_empty");

            full.powerDown();
            full.setDataRate(100);
            checkTrue(true, "power_down_and_resume");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
