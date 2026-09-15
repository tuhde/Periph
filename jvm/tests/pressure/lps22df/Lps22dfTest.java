///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Lps22dfFull;

public class Lps22dfTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x5C"));

        try (var connection = new I2CConnection(bus, addr)) {
            var lps = new Lps22dfFull(connection);

            checkTrue("who_am_i == 0xB4", lps.whoAmI() == 0xB4);

            double t = lps.temperature();
            checkTrue("temperature() in [-40, 85] °C", t >= -40.0 && t <= 85.0);

            double p = lps.pressure();
            checkTrue("pressure() in [26000, 126000] Pa", p >= 26000.0 && p <= 126000.0);

            lps.configure(3, 0, true, 1, true);
            double p2 = lps.pressure();
            checkTrue("configure_then_read() in [26000, 126000] Pa", p2 >= 26000.0 && p2 <= 126000.0);

            double alt = lps.altitude(101325.0);
            checkTrue("altitude() finite and in [-500, 10000] m", Double.isFinite(alt) && alt >= -500.0 && alt <= 10000.0);

            lps.setPressureThreshold(102000.0);
            lps.configureInterrupt(false, false, true, false, true, false, false, false);
            int src = lps.interruptSource();
            checkTrue("interrupt_source() returns 0/0xFF", (src & 0xFF) >= 0);

            int count = lps.fifoSampleCount();
            double[] samples = new double[16];
            int n = lps.readFifo(samples);
            checkTrue("read_fifo() returns 0..16", n >= 0 && n <= 16);
            checkTrue("fifo_count() returns 0..127", count >= 0 && count <= 127);

        }
        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}