///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL53L0XFull;
import it.uhde.periph.chips.tof.VL53L0XMinimal;

import java.util.Arrays;

public class VL53L0XTest {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x29"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new VL53L0XMinimal(connection);
            int d = minimal.distance();
            checkTrue(d >= 0 && d <= 8191, "distance_in_range");
            minimal.rangeValid();

            var s = new VL53L0XFull(connection);
            checkTrue(s.modelId() == 0xEE, "model_id");
            checkTrue(s.revisionId() > 0, "revision_id");
            int budget = s.timingBudget();
            checkTrue(budget >= 20000 && budget <= 40000, "default_budget");

            s.distance();
            var m = s.readMeasurement();
            checkTrue(m.rangeStatus() >= 0 && m.rangeStatus() <= 15 && m.signalRateMcps() >= 0, "measurement_record");

            s.setTimingBudget(50000);
            checkTrue(Math.abs(s.timingBudget() - 50000) < 300, "budget_roundtrip");
            s.setSignalRateLimit(0.1);
            checkTrue(Math.abs(s.signalRateLimit() - 0.1) < 0.01, "signal_rate_roundtrip");
            s.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 18);
            s.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE, 14);
            checkTrue(s.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE) == 18
                    && s.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE) == 14, "vcsel_roundtrip");
            s.setProfile(VL53L0XFull.Profile.DEFAULT);
            checkTrue(s.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE) == 14
                    && Math.abs(s.timingBudget() - 33000) < 300, "profile_default");

            double original = s.offset();
            s.setOffset(-10.25);
            checkTrue(s.offset() == -10.25, "offset_roundtrip");
            s.setOffset(original);

            s.setInterruptThresholds(100, 800);
            checkTrue(Arrays.equals(s.interruptThresholds(), new int[]{100, 800}), "thresholds_roundtrip");

            // Back-to-back continuous ranging, then timed mode.
            s.startContinuous();
            boolean ok = true;
            for (int i = 0; i < 3; i++) ok &= s.readContinuous() <= 8191;
            checkTrue(ok, "continuous_readings");
            s.stopContinuous();
            Thread.sleep(50);
            s.pollInterrupt();
            s.startContinuous(100);
            ok = true;
            for (int i = 0; i < 2; i++) ok &= s.readContinuous() <= 8191;
            checkTrue(ok, "timed_readings");
            s.stopContinuous();
            Thread.sleep(150);
            s.pollInterrupt();

            s.startContinuous();
            Thread.sleep(100);
            checkTrue(s.pollInterrupt() == VL53L0XFull.SOURCE_NEW_SAMPLE_READY, "poll_interrupt_new_sample");
            s.stopContinuous();
            Thread.sleep(50);
            s.pollInterrupt();

            s.recalibrate();
            checkTrue(true, "recalibrate");
            checkTrue(s.distance() <= 8191, "recalibrate_then_distance");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
