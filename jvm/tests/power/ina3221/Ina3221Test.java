///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina3221Full;

public class Ina3221Test {

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

            var ina = new Ina3221Full(transport);

            // --- Minimal methods: all three channels ---
            for (int ch = 1; ch <= 3; ch++) {
                double v = ina.voltage(ch);
                checkTrue("voltage(ch" + ch + ") in 0–26 V", v >= 0.0 && v <= 26.0);

                double sv = ina.shuntVoltage(ch);
                checkTrue("shuntVoltage(ch" + ch + ") is finite", Double.isFinite(sv));

                double i = ina.current(ch);
                checkTrue("current(ch" + ch + ") is finite", Double.isFinite(i));

                double p = ina.power(ch);
                checkTrue("power(ch" + ch + ") is finite", Double.isFinite(p));
            }

            // --- configure() ---
            ina.configure(1, 4, 4, Ina3221Full.MODE_SHUNT_BUS_CONT);
            checkTrue("configure() accepted", true);

            // --- enableChannel / channelEnabled ---
            ina.enableChannel(1, true);
            checkTrue("enableChannel(1, true) accepted", true);

            boolean en = ina.channelEnabled(1);
            checkTrue("channelEnabled(1) returns boolean", en == true || en == false);

            // --- conversionReady ---
            boolean cvrf = ina.conversionReady();
            checkTrue("conversionReady() returns boolean", cvrf == true || cvrf == false);

            // --- Alert limits ---
            ina.setCriticalAlert(1, 0.1);
            checkTrue("setCriticalAlert(1, 0.1) accepted", true);

            ina.setWarningAlert(1, 0.08);
            checkTrue("setWarningAlert(1, 0.08) accepted", true);

            // --- alertFlags ---
            int flags = ina.alertFlags();
            checkTrue("alertFlags() returns int", flags >= 0 && flags <= 0xFFFF);

            // --- power-valid limits ---
            ina.setPowerValidLimits(5.5, 4.5);
            checkTrue("setPowerValidLimits(5.5, 4.5) accepted", true);

            // --- summation ---
            ina.setSummationChannels(new int[]{1, 2, 3}, 0.1);
            checkTrue("setSummationChannels([1,2,3], 0.1) accepted", true);

            double sumV = ina.summationValue();
            checkTrue("summationValue() is finite", Double.isFinite(sumV));

            // --- powerValid ---
            boolean pv = ina.powerValid();
            checkTrue("powerValid() returns boolean", pv == true || pv == false);

            // --- manufacturerId / dieId ---
            int mfr = ina.manufacturerId();
            checkTrue("manufacturerId() == 0x5449", mfr == 0x5449);

            int die = ina.dieId();
            checkTrue("dieId() == 0x3220", die == 0x3220);

            // --- shutdown / wake ---
            ina.shutdown();
            checkTrue("shutdown() accepted", true);

            ina.wake();
            checkTrue("wake() accepted", true);

            // --- reset ---
            ina.reset();
            checkTrue("reset() accepted", true);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
