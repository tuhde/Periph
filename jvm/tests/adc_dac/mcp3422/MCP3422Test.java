///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.MCP3422Minimal;
import it.uhde.periph.chips.adc_dac.MCP3422Full;

/**
 * Hardware-in-the-loop test for the MCP3422. Needs an input signal within
 * ±2.048 V on CH1/CH2 (an open input reads noise, which still passes the range checks).
 */
public class MCP3422Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x68"));

        try (var connection = new I2CConnection(bus, addr)) {
            var adc = new MCP3422Minimal(connection);
            checkTrue(true, "construct_minimal");
            int raw = adc.readRaw(0);
            checkTrue(raw >= -32768 && raw <= 32767, "raw_in_16_bit_range");
            double volts = adc.readVoltage(0);
            checkTrue(volts >= -2.048 && volts <= 2.048, "voltage_in_range");
            checkTrue(adc.readAll().length == 2, "read_all_two_values");

            var full = new MCP3422Full(connection);
            full.setResolution(12);
            full.setGain(2);
            checkTrue(full.getResolution() == 12 && full.getGain() == 2, "selection_roundtrip");
            checkTrue(Math.abs(full.fullScale() - 1.024) < 1e-9, "full_scale");
            int one = full.readRaw(0);
            checkTrue(one >= -2048 && one <= 2047, "one_shot_12_bit_range");

            full.startContinuous();
            checkTrue(full.isContinuous(), "continuous_flag");
            int c = full.readContinuousRaw();
            checkTrue(c >= -2048 && c <= 2047, "continuous_sample_in_range");
            checkTrue((full.readConfig() & 0x10) != 0, "read_config_rdy_bit");
            full.stopContinuous();
            checkTrue(!full.isContinuous(), "stopped");
            checkTrue(full.readScan(new int[]{0, 1}).length == 2, "read_scan_length");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
