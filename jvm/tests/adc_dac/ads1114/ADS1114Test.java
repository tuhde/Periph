///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1114Minimal;
import it.uhde.periph.chips.adc_dac.ADS1114Full;

public class ADS1114Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x48"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new ADS1114Minimal(connection);
            int raw = minimal.readRaw();
            int raw2 = minimal.readRaw();
            checkTrue(Math.abs(raw - raw2) < 4000, "read_raw_stable");
            double v = minimal.readVoltage();
            checkTrue(Math.abs(v - raw * 0.0000625) < 0.25, "read_voltage_matches_raw");

            var full = new ADS1114Full(connection);
            full.setDataRate(ADS1114Full.DR_860);
            checkTrue(full.getDataRate() == 7 && full.dataRateSps() == 860, "data_rate_roundtrip");
            full.setGain(ADS1114Full.PGA_4_096);
            checkTrue(full.getGain() == 1 && full.fullScale() == 4.096, "gain_roundtrip");
            full.setGain(ADS1114Full.PGA_2_048);
            full.startContinuous();
            Thread.sleep(50);
            int cont = full.readContinuousRaw();
            checkTrue(Math.abs(cont - raw) < 8000, "continuous_matches_single_shot");
            checkTrue((full.readConfig() & 0x0100) == 0, "continuous_config_mode");
            full.stopContinuous();
            checkTrue((full.readConfig() & 0x0100) != 0, "single_shot_config_mode");
            full.setLowThresholdRaw(-1000);
            full.setHighThresholdRaw(1000);
            var th = full.getThresholdsRaw();
            checkTrue(th[0] == -1000 && th[1] == 1000, "threshold_roundtrip");
            full.configureComparator(ADS1114Full.COMP_WINDOW, ADS1114Full.ACTIVE_LOW, false, ADS1114Full.QUEUE_4);
            checkTrue((full.readConfig() & 0x0017) == 0x0012, "comparator_bits");
            full.disableComparator();
            checkTrue((full.readConfig() & 0x0003) == 0x0003, "comparator_disabled");
            full.reset();
            checkTrue((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
