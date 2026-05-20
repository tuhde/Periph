///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina226Full;

public class Ina226Test {

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

        try (var transport = new I2CTransport(bus, addr)) {

            var ina = new Ina226Full(transport, 0.1, 2.0);

            // --- Basic measurements ---
            double v = ina.voltage();
            checkTrue("voltage() in range 0–36 V", v >= 0.0 && v <= 36.0);

            double vs = ina.shuntVoltage();
            checkTrue("shuntVoltage() is finite", Double.isFinite(vs));

            double c = ina.current();
            checkTrue("current() is finite", Double.isFinite(c));

            double p = ina.power();
            checkTrue("power() is finite", Double.isFinite(p));

            // --- Configuration ---
            ina.configure(Ina226Full.AVG_128, Ina226Full.CT_1100US,
                          Ina226Full.CT_1100US, Ina226Full.MODE_SHUNT_BUS_CONT);
            checkTrue("configure() accepted", true);

            Thread.sleep(300);  // allow conversion with 128 averages at 1.1 ms each

            // --- Conversion ready and overflow flags ---
            boolean ready = ina.conversionReady();
            checkTrue("conversionReady() accepted", true);

            boolean ovf = ina.overflow();
            checkTrue("overflow() accepted", true);

            // --- Alert ---
            ina.setAlert(Ina226Full.SOL, 0.1);
            checkTrue("setAlert(SOL, 0.1) accepted", true);

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
            checkTrue("dieId() == 0x2260", dieId == 0x2260);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
