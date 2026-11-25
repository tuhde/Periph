///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.accelerometer.MMA8451QFull;
import it.uhde.periph.chips.accelerometer.MMA8451QMinimal;
import it.uhde.periph.connection.I2CConnection;

public class MMA8451QTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x1C").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new MMA8451QMinimal(connection);
            checkTrue("construct_minimal", true);

            double[] xyz = chip.read();
            checkTrue("read_returns_doubles", xyz.length == 3);
            double mag = Math.sqrt(xyz[0]*xyz[0] + xyz[1]*xyz[1] + xyz[2]*xyz[2]);
            checkTrue("magnitude_near_1g", mag >= 0.5 && mag <= 1.5);

            var chipFull = new MMA8451QFull(connection);
            checkTrue("construct_full", true);

            chipFull.setRange(4);
            double[] xyz2 = chipFull.read();
            checkTrue("read_after_set_range_4g", xyz2.length == 3);

            int[] raw = chipFull.readRaw();
            checkTrue("read_raw_returns_ints", raw.length == 3);

            int status = chipFull.readStatus();
            checkTrue("read_status_in_range", status >= 0 && status <= 0xFF);

            int mode = chipFull.readSystemMode();
            checkTrue("system_mode_is_known", mode >= 0 && mode <= 2);

            chipFull.setDataRate(200);
            checkTrue("set_data_rate", true);

            chipFull.setOrientation(true, 100);
            var orientation = chipFull.readOrientation();
            checkTrue("read_orientation_in_range",
                    orientation.portraitLandscape() >= 0 && orientation.portraitLandscape() <= 3);

            chipFull.enableInterrupt(MMA8451QFull.SOURCE_DATA_READY, 1);
            int irq = chipFull.pollInterrupt();
            checkTrue("poll_interrupt_in_range", irq >= 0 && irq <= 0xFF);
            chipFull.disableInterrupt(MMA8451QFull.SOURCE_DATA_READY);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
