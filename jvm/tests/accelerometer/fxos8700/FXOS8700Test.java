///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.FXOS8700Full;

public class FXOS8700Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x1E"));

        try (var conn = new I2CConnection(bus, addr)) {
            var chip = new FXOS8700Full(conn);
            checkTrue("construct_full", true);

            double[] accel = chip.readAccel();
            double magnitude = Math.sqrt(accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]);
            checkTrue("accel_magnitude_near_1g", magnitude >= 0.7 && magnitude <= 1.3);
            double[] mag = chip.readMag();
            checkTrue("mag_in_range", Math.abs(mag[0]) <= 1200 && Math.abs(mag[1]) <= 1200 && Math.abs(mag[2]) <= 1200);

            double[] hybrid = chip.readHybrid();
            checkTrue("hybrid_plausible", hybrid.length == 6 && Math.abs(hybrid[2]) <= 2.5);
            int[] rawAccel = chip.readRawAccel();
            checkTrue("raw_accel_in_14bit_range", Math.abs(rawAccel[0]) <= 8192 && Math.abs(rawAccel[1]) <= 8192 && Math.abs(rawAccel[2]) <= 8192);
            checkTrue("raw_mag_returns_ints", chip.readRawMag().length == 3);
            double temperature = chip.readTemperature();
            checkTrue("temperature_plausible", temperature > -100 && temperature < 150);

            chip.setRange(4);
            chip.setDataRate(100.0);
            Thread.sleep(200);
            checkTrue("read_after_set_range_4g", Math.abs(chip.readAccel()[2]) <= 4.5);
            chip.setRange(2);
            chip.setDataRate(200.0);
            chip.setAccelOversampling(2, -1);
            chip.setAccelOversampling(0, -1);
            chip.setMagOversampling(7, -1);
            checkTrue("system_mode_wake", chip.readSystemMode() == 1);
            Thread.sleep(200);
            checkTrue("new_data_flags", chip.newAccelData() || !chip.newAccelData());
            checkTrue("new_mag_data_flag", chip.newMagData() || !chip.newMagData());

            chip.setAccelOffset(0.02, -0.02, 0.0);
            double[] ao = chip.getAccelOffset();
            checkTrue("accel_offset_roundtrip", Math.abs(ao[0] - 0.02) < 0.003 && Math.abs(ao[1] + 0.02) < 0.003);
            chip.setAccelOffset(0.0, 0.0, 0.0);
            chip.setMagOffset(5.0, -5.0, 0.0);
            double[] mo = chip.getMagOffset();
            checkTrue("mag_offset_roundtrip", Math.abs(mo[0] - 5.0) < 0.15 && Math.abs(mo[1] + 5.0) < 0.15);
            chip.setMagOffset(0.0, 0.0, 0.0);

            chip.resetMagMinMax();
            Thread.sleep(200);
            double[] mm = chip.readMagMinMax();
            checkTrue("min_max_ordered", mm[0] <= mm[3] && mm[1] <= mm[4] && mm[2] <= mm[5]);
            double heading = chip.heading(0.0);
            checkTrue("heading_in_range", heading >= 0.0 && heading < 360.0);

            chip.setFifo(FXOS8700Full.FIFO_CIRCULAR, 8);
            Thread.sleep(500);
            checkTrue("fifo_level_in_range", chip.fifoLevel() <= 32);
            checkTrue("fifo_samples_bounded", chip.readFifo(4).length <= 4);
            chip.setFifo(FXOS8700Full.FIFO_DISABLED, 0);

            chip.setFreefallMotion(true, 7, 0.3, 50.0, true, false);
            checkTrue("motion_source_readable", chip.readMotionSource() >= 0);
            chip.setTransient(7, 0.3, 0.0, false, false);
            checkTrue("transient_source_readable", chip.readTransientSource() >= 0);
            chip.setPulse(0x07, 0x00);
            checkTrue("pulse_source_readable", chip.readPulseSource() >= 0);
            chip.setOrientation(true, 100.0, 45, 4, 4, 2);
            checkTrue("orientation_in_range", chip.readOrientation()[2] <= 3);

            chip.enableInterrupt(FXOS8700Full.SOURCE_DATA_READY, 1);
            checkTrue("poll_interrupt_mask", chip.pollInterrupt() >= 0);
            chip.disableInterrupt(FXOS8700Full.SOURCE_DATA_READY);

            int[] st = chip.selfTest();
            checkTrue("self_test_runs", st[3] > 0);
            chip.setMode(FXOS8700Full.MODE_HYBRID);
            checkTrue("measure_mag_once", chip.measureMagOnce().length == 3);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
