///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-java:1.5.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ina260Full;

public class Ina260Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {

            var ina = new Ina260Full(connection);

            // --- Basic measurements ---
            double v = ina.voltage();
            checkTrue("voltage() in range 0–36 V", v >= 0.0 && v <= 36.0);


            double c = ina.current();
            checkTrue("current() is finite", Double.isFinite(c));

            double p = ina.power();
            checkTrue("power() is finite", Double.isFinite(p));

            // --- Configuration ---
            ina.configure(Ina260Full.AVG_128, Ina260Full.CT_1100US,
                          Ina260Full.CT_1100US, Ina260Full.MODE_SHUNT_BUS_CONT);
            checkTrue("configure() accepted", true);

            Thread.sleep(300);  // allow conversion with 128 averages at 1.1 ms each

            // --- Conversion ready and overflow flags ---
            boolean ready = ina.conversionReady();
            checkTrue("conversionReady() accepted", true);

            boolean ovf = ina.overflow();
            checkTrue("overflow() accepted", true);

            // --- Alert ---
            ina.setAlert(Ina260Full.OCL, 5.0);
            checkTrue("setAlert(OCL, 5.0) accepted", true);

            int flags = ina.alertFlags();
            checkTrue("alertFlags() accepted", true);

            // --- Reset ---
            ina.reset();
            checkTrue("reset() accepted", true);

            // --- Shutdown / wake ---
            ina.shutdown();
            checkTrue("shutdown() accepted", true);

            Thread.sleep(10);

            ina.wake();
            checkTrue("wake() accepted", true);

            // --- Device identification ---
            int mfrId = ina.manufacturerId();
            checkTrue("manufacturerId() == 0x5449", mfrId == 0x5449);

            int dieId = ina.dieId();
            checkTrue("(dieId() & 0xFFF0) == 0x2270", (dieId & 0xFFF0) == 0x2270);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
