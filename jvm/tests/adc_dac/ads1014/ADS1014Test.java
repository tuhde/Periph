///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-java:1.3.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1014Minimal;
import it.uhde.periph.chips.adc_dac.ADS1014Full;

public class ADS1014Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x48"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new ADS1014Minimal(connection);
            checkTrue(true, "construct_minimal");
            int raw = minimal.readRaw();
            checkTrue(raw >= -2048 && raw <= 2047, "raw_in_range");
            checkTrue(Math.abs(minimal.readVoltage() - 0.001 * raw) < 0.05, "voltage_matches_raw");

            var full = new ADS1014Full(connection);
            full.reset();
            checkTrue((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");

            full.setGain(ADS1014Full.PGA_0_256);
            checkTrue(full.getGain() == ADS1014Full.PGA_0_256 && full.fullScale() == 0.256, "gain_roundtrip");
            full.readRaw();
            checkTrue(((full.readConfig() >> 9) & 0x7) == ADS1014Full.PGA_0_256, "config_gain_bits");
            full.setGain(ADS1014Full.PGA_2_048);

            full.setDataRate(ADS1014Full.DR_250);
            checkTrue(full.getDataRate() == ADS1014Full.DR_250 && full.dataRateSps() == 250, "dr_roundtrip");
            full.readRaw();
            checkTrue(((full.readConfig() >> 5) & 0x7) == ADS1014Full.DR_250, "config_dr_bits");
            full.setDataRate(ADS1014Full.DR_1600);

            full.startContinuous();
            Thread.sleep(50);
            int cont = full.readContinuousRaw();
            checkTrue(cont >= -2048 && cont <= 2047, "continuous_raw_in_range");
            checkTrue((full.readConfig() & 0x0100) == 0, "continuous_mode_bit");
            full.stopContinuous();
            checkTrue((full.readConfig() & 0x0100) != 0, "stop_mode_bit");

            full.setLowThresholdRaw(-100);
            full.setHighThresholdRaw(300);
            checkTrue(java.util.Arrays.equals(full.getThresholdsRaw(), new int[]{-100, 300}), "threshold_roundtrip");
            full.setThresholds(-0.2, 0.5);
            checkTrue(java.util.Arrays.equals(full.getThresholdsRaw(), new int[]{-200, 500}), "threshold_volts_roundtrip");

            full.configureComparator(ADS1014Full.COMP_WINDOW, ADS1014Full.ACTIVE_HIGH, true, ADS1014Full.QUEUE_2);
            checkTrue((full.readConfig() & 0x1F) == (0x10 | 0x08 | 0x04 | 0x01), "comparator_bits");
            full.clearAlert();
            full.disableComparator();
            checkTrue((full.readConfig() & 0x3) == 3, "comparator_disabled");

            full.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);
            checkTrue(java.util.Arrays.equals(full.getThresholdsRaw(), new int[]{0, -2048}), "ready_thresholds");
            checkTrue((full.readConfig() & 0x3) == 0, "ready_que_00");
            full.disableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);

            full.reset();
            checkTrue((full.readConfig() & 0x7FFF) == 0x0583, "final_reset");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
