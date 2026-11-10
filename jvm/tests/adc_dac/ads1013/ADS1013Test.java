///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1013Minimal;
import it.uhde.periph.chips.adc_dac.ADS1013Full;

public class ADS1013Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x48"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new ADS1013Minimal(connection);
            checkTrue(true, "construct_minimal");
            int raw = minimal.readRaw();
            checkTrue(raw >= -2048 && raw <= 2047, "raw_in_range");
            checkTrue(Math.abs(minimal.readVoltage() - 0.001 * raw) < 0.05, "voltage_matches_raw");

            var full = new ADS1013Full(connection);
            full.reset();
            checkTrue((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");
            checkTrue(full.fullScale() == 2.048 && full.lsb() == 0.001, "full_scale_fixed");

            full.setDataRate(ADS1013Full.DR_250);
            checkTrue(full.getDataRate() == ADS1013Full.DR_250 && full.dataRateSps() == 250, "dr_roundtrip");
            full.readRaw();
            checkTrue(((full.readConfig() >> 5) & 0x7) == ADS1013Full.DR_250, "config_dr_bits");
            full.setDataRate(ADS1013Full.DR_1600);

            full.startContinuous();
            Thread.sleep(50);
            int cont = full.readContinuousRaw();
            checkTrue(cont >= -2048 && cont <= 2047, "continuous_raw_in_range");
            checkTrue((full.readConfig() & 0x0100) == 0, "continuous_mode_bit");
            full.stopContinuous();
            checkTrue((full.readConfig() & 0x0100) != 0, "stop_mode_bit");

            full.reset();
            checkTrue((full.readConfig() & 0x7FFF) == 0x0583, "final_reset");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
