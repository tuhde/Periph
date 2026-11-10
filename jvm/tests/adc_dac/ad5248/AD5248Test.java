///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.AD5248Full;

public class AD5248Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x2C").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new AD5248Full(connection, 10000.0, 160.0);

            chip.setWiper(0, 100);
            checkTrue("set/get wiper ch0", chip.getWiper(0) == 100);
            chip.setWiper(1, 200);
            checkTrue("set/get wiper ch1", chip.getWiper(1) == 200);
            checkTrue("ch0 unchanged by ch1 write", chip.getWiper(0) == 100);
            chip.setResistance(0, 5320.0);
            checkTrue("set_resistance midscale code", chip.getWiper(0) == 128);
            checkTrue("get_resistance ~5320", Math.abs(chip.getResistance(0) - 5320.0) < 1.0);
            chip.setBoth(10, 20);
            checkTrue("set_both ch0", chip.getWiper(0) == 10);
            checkTrue("set_both ch1", chip.getWiper(1) == 20);
            chip.setRatio(0, 0.5);
            checkTrue("get_ratio ~0.5", Math.abs(chip.getRatio(0) - 0.5) < 0.01);
            chip.setWiper(0, 250);
            checkTrue("increment clamps", chip.increment(0, 10) == 255);
            chip.setWiper(1, 77);
            chip.shutdown(1);
            checkTrue("is_shutdown", chip.isShutdown(1));
            chip.resume(1);
            checkTrue("RDAC kept across shutdown", chip.getWiper(1) == 77);
            chip.reset();
            checkTrue("reset ch0", chip.getWiper(0) == 0x80);
            checkTrue("reset ch1", chip.getWiper(1) == 0x80);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
