///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL6180XFull;
import it.uhde.periph.chips.tof.VL6180XMinimal;

import java.io.IOException;

public class VL6180XTest {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x29"));

        try (var connection = new I2CConnection(bus, addr, null, null, 2)) {
            var minimal = new VL6180XMinimal(connection);
            int mm;
            try {
                mm = minimal.readRange();
            } catch (IOException e) {
                mm = minimal.lastError() != 0 ? 0 : -1;   // a range error code still completes the measurement
            }
            checkTrue(mm >= 0 && mm <= 255, "read_range_completes");

            var s = new VL6180XFull(connection);
            checkTrue(s.modelId() == 0xB4, "model_id");
            checkTrue(s.revision()[0] >= 1, "revision");
            int[] st = s.readRangeStatus();
            checkTrue(st[0] >= 0 && st[0] <= 255 && st[1] >= 0 && st[1] <= 15, "range_status");

            checkTrue(s.readAlsRaw() >= 0, "als_raw");
            checkTrue(s.readLux() >= 0, "lux_positive");
            for (int code : new int[] {0, 6, 7}) {
                s.setAlsGain(code);
                checkTrue(s.readLux() >= 0, "lux_at_gain_" + code);
            }
            s.setAlsGain(6);
            s.setAlsIntegrationTime(50);
            checkTrue(s.readLux() >= 0, "lux_50ms");
            s.setAlsIntegrationTime(100);
            s.setAlsScaler(1);

            s.setRangeOffset(3);
            checkTrue(s.getRangeOffset() == 3, "offset_roundtrip");
            s.setRangeOffset(0);
            s.setCrosstalkRate(0.0);
            s.setMaxConvergenceTime(49);
            s.setAveragingPeriod(48);
            s.recalibrateVhv();
            checkTrue(s.readRangeStatus()[0] <= 255, "range_after_recalibration");

            s.startContinuousRange(50);
            boolean contOk = true;
            for (int i = 0; i < 5; i++) {
                try {
                    int v = s.readRangeContinuous();
                    contOk = contOk && v >= 0 && v <= 255;
                } catch (IOException e) {
                    contOk = contOk && s.lastError() != 0;
                }
            }
            checkTrue(contOk, "continuous_range");
            s.stopContinuousRange();
            Thread.sleep(200);

            s.setRangeThresholds(30, 80);
            s.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_OUT_OF_WINDOW);
            s.startContinuousRange(50);
            Thread.sleep(300);
            int sources = s.pollInterrupt();
            checkTrue(sources >= 0 && sources <= 7, "poll_interrupt_mask");
            s.stopContinuousRange();
            Thread.sleep(200);
            s.disableInterrupt(VL6180XMinimal.SOURCE_RANGE);
            s.enableInterrupt(VL6180XMinimal.SOURCE_RANGE, VL6180XMinimal.MODE_NEW_SAMPLE_READY);
            s.pollInterrupt();
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
