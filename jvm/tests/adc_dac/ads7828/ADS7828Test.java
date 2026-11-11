///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS7828Full;

public class ADS7828Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x48"));

        try (var connection = new I2CConnection(bus, addr)) {
            var adc = new ADS7828Full(connection);
            adc.init();

            checkTrue(adc.readRaw(0) <= 4095, "read_raw_in_range");
            checkTrue(adc.readRaw(99) <= 4095, "read_raw_clamped");
            double v = adc.readVoltage(1);
            checkTrue(v >= 0.0 && v <= 2.5, "read_voltage_in_range");
            double[] all = adc.readAll();
            boolean allOk = all.length == 8;
            for (double x : all) if (x < 0.0 || x > 2.5) allOk = false;
            checkTrue(allOk, "read_all_in_range");
            checkTrue(adc.readDifferentialRaw(0, false) <= 4095, "read_differential_raw_in_range");
            checkTrue(adc.readDifferential(1, true) >= 0.0, "read_differential_inverted_nonneg");
            double sv = adc.readSignedDifferential(2);
            checkTrue(sv >= -2.5 && sv <= 2.5, "read_signed_differential_in_range");
            checkTrue(Math.abs(adc.lsb() - 2.5 / 4096) < 1e-9, "lsb");
            checkTrue(adc.usesInternalReference() && adc.getReference() == 2.5, "internal_reference");
            int[] scan = adc.readScan(new int[]{0, 3, 7});
            boolean scanOk = scan.length == 3;
            for (int r : scan) if (r > 4095) scanOk = false;
            checkTrue(scanOk, "read_scan_in_range");
            adc.setPowerMode(ADS7828Full.REF_ON_ADC_ON);
            checkTrue(true, "set_power_mode_ok");
            boolean rejected = false;
            try { adc.setPowerMode(ADS7828Full.REF_OFF_ADC_ON); } catch (IllegalArgumentException e) { rejected = true; }
            checkTrue(rejected, "internal_reference_rejects_pd1_clear");
            adc.setReference(5.0);
            checkTrue(!adc.usesInternalReference() && adc.getReference() == 5.0, "external_reference");
            adc.setReference(null);
            checkTrue(adc.usesInternalReference(), "internal_reference_restored");
            adc.powerDown();
            checkTrue(adc.readRaw(0) <= 4095, "read_after_power_down");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
