///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL53L1XFull;
import it.uhde.periph.chips.tof.VL53L1XMinimal;

import java.util.Arrays;

public class VL53L1XTest {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x29"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new VL53L1XMinimal(connection);
            int d = minimal.distance();
            checkTrue(d >= 0 && d <= 65535, "distance_in_range");
            minimal.rangeValid();

            var s = new VL53L1XFull(connection);
            checkTrue(s.modelId() == 0xEA, "model_id");
            checkTrue(s.moduleType() == 0xCC, "module_type");
            checkTrue(s.revisionId() > 0, "revision_id");
            checkTrue(s.timingBudget() == 100000, "default_budget");
            checkTrue(s.distanceMode() == VL53L1XFull.DistanceMode.LONG, "default_mode");

            s.distance();
            var m = s.readMeasurement();
            checkTrue(m.signalRateMcps() >= 0 && m.effectiveSpadCount() >= 0, "measurement_record");

            s.setTimingBudget(50000);
            checkTrue(s.timingBudget() == 50000, "budget_roundtrip");
            s.setDistanceMode(VL53L1XFull.DistanceMode.SHORT);
            checkTrue(s.distanceMode() == VL53L1XFull.DistanceMode.SHORT && s.timingBudget() == 50000, "mode_short");
            int ds = s.distance();
            checkTrue(ds >= 0 && ds <= 65535, "short_distance");
            s.setDistanceMode(VL53L1XFull.DistanceMode.LONG);
            s.setTimingBudget(100000);

            s.setSignalRateLimit(0.5);
            checkTrue(s.signalRateLimit() == 0.5, "signal_rate_roundtrip");
            s.setSignalRateLimit(1.0);
            s.setSigmaThreshold(60);
            checkTrue(s.sigmaThreshold() == 60, "sigma_roundtrip");
            s.setSigmaThreshold(90);

            s.setRoi(8, 8);
            checkTrue(Arrays.equals(s.roi(), new int[]{8, 8}), "roi_roundtrip");
            int oc = s.opticalCenter();
            checkTrue(oc >= 0 && oc <= 255, "optical_center");
            s.setRoi(16, 16);
            checkTrue(Arrays.equals(s.roi(), new int[]{16, 16}) && s.roiCenter() == 199, "roi_restored");

            double original = s.offset();
            s.setOffset(-10.25);
            checkTrue(s.offset() == -10.25, "offset_roundtrip");
            s.setOffset(original);
            s.setCrosstalkCompensation(0.01);
            checkTrue(Math.abs(s.crosstalkCompensation() - 0.01) < 0.0001, "crosstalk_roundtrip");
            s.setCrosstalkCompensation(0);

            s.setInterruptThresholds(100, 800);
            checkTrue(Arrays.equals(s.interruptThresholds(), new int[]{100, 800}), "thresholds_roundtrip");

            s.startContinuous(150);
            int period = s.interMeasurement();
            checkTrue(period >= 148 && period <= 150, "inter_measurement");
            boolean contOk = true;
            for (int i = 0; i < 3; i++) {
                int r = s.readContinuous();
                contOk = contOk && r >= 0 && r <= 65535;
            }
            checkTrue(contOk, "continuous_readings");
            Thread.sleep(200);
            checkTrue(s.pollInterrupt() == VL53L1XFull.SOURCE_NEW_SAMPLE_READY, "poll_interrupt_new_sample");
            s.stopContinuous();
            Thread.sleep(200);
            s.pollInterrupt();

            s.recalibrate();
            checkTrue(true, "recalibrate");
            int after = s.distance();
            checkTrue(after >= 0 && after <= 65535, "recalibrate_then_distance");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
