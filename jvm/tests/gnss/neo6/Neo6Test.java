///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.UARTTransport;
import it.uhde.periph.chips.gnss.Neo6Minimal;

public class Neo6Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    // Requires a NEO-6 module wired to UART with a clear sky view. Achieving
    // an actual fix needs an outdoor antenna and can take up to ~26 s (cold
    // start); this test only requires that well-typed values come back, not
    // a fix.
    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("UART_PORT", "/dev/ttyS0");

        try (var transport = new UARTTransport(port)) {
            var gps = new Neo6Minimal(transport);

            checkTrue("fix() starts at 0", gps.fix() == 0);
            checkTrue("latitude() starts at null", gps.latitude() == null);

            for (int i = 0; i < 3000; i++) {
                gps.update();
            }

            int fix = gps.fix();
            checkTrue("fix() is a valid quality code", fix == 0 || fix == 1 || fix == 2);
            checkTrue("satellites() is non-negative", gps.satellites() >= 0);

            if (fix > 0) {
                checkTrue("latitude() in range once fixed",
                        gps.latitude() >= -90.0 && gps.latitude() <= 90.0);
                checkTrue("longitude() in range once fixed",
                        gps.longitude() >= -180.0 && gps.longitude() <= 180.0);
                checkTrue("altitude() is populated once fixed", gps.altitude() != null);
            } else {
                System.out.println("note: no fix acquired during the test window (needs sky view)");
            }
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
