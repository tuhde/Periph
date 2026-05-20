///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina219Full;

public class Ina219Test {

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

            var ina = new Ina219Full(transport, 0.1, 2.0);

            // voltage() should return a value in the physically plausible range 0–36 V
            double v = ina.voltage();
            checkTrue("voltage() in range [0, 36] V", v >= 0.0 && v <= 36.0);

            // shuntVoltage() should return a float (any finite value accepted)
            double vs = ina.shuntVoltage();
            checkTrue("shuntVoltage() is finite", Double.isFinite(vs));

            // current() should return a float (signed, any finite value)
            double i = ina.current();
            checkTrue("current() is finite", Double.isFinite(i));

            // power() should return a non-negative float
            double p = ina.power();
            checkTrue("power() is finite", Double.isFinite(p));

            // configure() should be accepted without throwing
            ina.configure(Ina219Full.BRNG_32V, Ina219Full.PGA_8,
                          Ina219Full.ADC_12BIT, Ina219Full.ADC_12BIT,
                          Ina219Full.MODE_SHUNT_BUS_CONT);
            checkTrue("configure() accepted", true);

            // conversionReady() should return a boolean
            ina.conversionReady();
            checkTrue("conversionReady() accepted", true);

            // overflow() should return a boolean
            ina.overflow();
            checkTrue("overflow() accepted", true);

            // reset() should be accepted without throwing
            ina.reset();
            checkTrue("reset() accepted", true);
            Thread.sleep(5);

            // shutdown() should be accepted without throwing
            ina.shutdown();
            checkTrue("shutdown() accepted", true);
            Thread.sleep(5);

            // wake() should restore the device
            ina.wake();
            checkTrue("wake() accepted", true);
            Thread.sleep(5);

            // After wake, voltage should still be in range
            double vAfterWake = ina.voltage();
            checkTrue("voltage() after wake in range [0, 36] V",
                      vAfterWake >= 0.0 && vAfterWake <= 36.0);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
