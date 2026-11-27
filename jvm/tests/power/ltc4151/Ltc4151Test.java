///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ltc4151Full;

public class Ltc4151Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x6A").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var ltc = new Ltc4151Full(connection, 0.02);

            double v = ltc.voltage();
            checkTrue("voltage() in range 7-80 V", v >= 7.0 && v <= 80.0);
            double c = ltc.current();
            checkTrue("current() in range", c >= 0.0 && c <= 4.096);
            double p = ltc.power();
            checkTrue("power() in range", p >= 0.0 && p <= 330.0);
            double sv = ltc.senseVoltage();
            checkTrue("senseVoltage() in range", sv >= 0.0 && sv <= 0.08192);
            double av = ltc.adinVoltage();
            checkTrue("adinVoltage() in range", av >= 0.0 && av <= 2.048);
            checkTrue("readAll() vin in range", ltc.readAll().vin >= 7.0);
            int raw = ltc.readRaw(Ltc4151Full.VIN);
            checkTrue("readRaw(VIN) in range", raw > 0 && raw < 4096);

            checkTrue("readControl() default 0x0C", ltc.readControl() == 0x0C);
            checkTrue("not in snapshot mode", !ltc.isSnapshotMode());
            double s = ltc.snapshot(Ltc4151Full.VIN);
            checkTrue("snapshot(VIN) in range", s >= 7.0 && s <= 80.0);
            checkTrue("back in continuous scan", !ltc.isSnapshotMode());

            ltc.setStuckBusTimer(false);
            checkTrue("stuck-bus timer off", ltc.readControl() == 0x08);
            ltc.setStuckBusTimer(true);
            checkTrue("stuck-bus timer on", ltc.readControl() == 0x0C);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
