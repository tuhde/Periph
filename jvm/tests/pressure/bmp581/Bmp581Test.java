///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Bmp581Minimal;
import it.uhde.periph.chips.pressure.Bmp581Full;

public class Bmp581Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
            System.getenv().getOrDefault("I2C_ADDR", "0x46").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var bmp = new Bmp581Minimal(connection);
            double t = bmp.temperature();
            checkTrue(t >= -40.0 && t <= 85.0, "temperature_range");
            double p = bmp.pressure();
            checkTrue(p >= 30000.0 && p <= 125000.0, "pressure_range");

            try (var connection2 = new I2CConnection(bus, addr)) {
                var bmpFull = new Bmp581Full(connection2);
                bmpFull.configure(0x1C, Bmp581Full.OSR_1X, Bmp581Full.OSR_1X, true);
                bmpFull.setMode(Bmp581Full.MODE_NORMAL);
                double alt = bmpFull.altitude();
                checkTrue(alt >= -500.0 && alt <= 9000.0, "altitude_range");
                checkTrue(bmpFull.chipId() == 0x50, "chip_id");
                bmpFull.softwareReset();
                checkTrue(true, "software_reset");
            }
        }
        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        if (failed != 0) System.exit(1);
    }
}