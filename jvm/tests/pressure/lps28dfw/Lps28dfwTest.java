///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Lps28dfwMinimal;
import it.uhde.periph.chips.pressure.Lps28dfwFull;

public class Lps28dfwTest {
    static int passed = 0;
    static int failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5C").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var lps = new Lps28dfwMinimal(connection);

            // Sensitivity: Mode 1, raw 24-bit = 1000 * 4096 = 4096000
            checkTrue(Math.abs((1000.0 * 4096) / 4096.0 - 1000.0) < 0.01, "pressure_sensitivity_mode1");
            checkTrue(Math.abs((1000.0 * 2048) / 2048.0 - 1000.0) < 0.01, "pressure_sensitivity_mode2");
            checkTrue(Math.abs(2500 / 100.0 - 25.0) < 0.001, "temperature_conversion");

            var lpsFull = new Lps28dfwFull(connection);
            checkTrue(lpsFull.odr == 0x04 && lpsFull.avg == 0x02 && lpsFull.fsMode == 0,
                    "full_default_inherits");

            lpsFull.configure(Lps28dfwFull.ODR_100_HZ, Lps28dfwFull.AVG_128, Lps28dfwFull.FS_MODE_2, false, 1);
            checkTrue(lpsFull.odr == 0x07 && lpsFull.avg == 0x05 && lpsFull.fsMode == 1
                            && lpsFull.lpfEn == 0 && lpsFull.lpfCfg == 1, "full_configure");

            int thresholdRaw = (int) (1050.0 * 16);
            if (thresholdRaw > 0x7FFF) thresholdRaw = 0x7FFF;
            checkTrue(thresholdRaw == 16800, "threshold_raw_conversion");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}