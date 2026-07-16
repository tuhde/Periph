///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.adc_dac.Pcf8591Full;

public class Pcf8591Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x48").replaceFirst("^0[xX]", ""), 16);

        try (var transport = new I2CTransport(bus, addr)) {
            var adc = new Pcf8591Full(transport);

            int ch0 = adc.readChannel(0);
            checkTrue("readChannel(0) in [0, 255]", ch0 >= 0 && ch0 <= 255);

            int ch3 = adc.readChannel(3);
            checkTrue("readChannel(3) in [0, 255]", ch3 >= 0 && ch3 <= 255);

            int chOob = adc.readChannel(99);
            checkTrue("readChannel(99) clamped to valid range", chOob >= 0 && chOob <= 255);

            int[] allRaw = adc.readAll();
            checkTrue("readAll returns 4 values", allRaw.length == 4);
            for (int v : allRaw) {
                checkTrue("readAll value in [0, 255]", v >= 0 && v <= 255);
            }

            double v0 = adc.readChannelVoltage(0, 3.3, 0.0);
            checkTrue("readChannelVoltage in [0, 3.3]", v0 >= 0.0 && v0 <= 3.3);

            double[] vAll = adc.readAllVoltage(3.3, 0.0);
            checkTrue("readAllVoltage returns 4 doubles", vAll.length == 4);
            for (double v : vAll) {
                checkTrue("readAllVoltage value in [0, 3.3]", v >= 0.0 && v <= 3.3);
            }

            adc.configure(Pcf8591Full.MODE_4_SINGLE_ENDED, false, false);
            checkTrue("configure 4 single-ended accepted", true);

            adc.configure(Pcf8591Full.MODE_3_DIFFERENTIAL, false, false);
            int diff = adc.readDifferential(0);
            checkTrue("readDifferential in [-128, 127]", diff >= -128 && diff <= 127);

            adc.configure(Pcf8591Full.MODE_MIXED, false, false);
            checkTrue("configure mixed mode accepted", true);

            adc.configure(Pcf8591Full.MODE_2_DIFFERENTIAL, false, false);
            checkTrue("configure 2 differential accepted", true);

            adc.configure(Pcf8591Full.MODE_4_SINGLE_ENDED, true, false);
            int[] auto = adc.readAll();
            checkTrue("readAll with auto-increment returns 4 values", auto.length == 4);

            adc.configure(Pcf8591Full.MODE_4_SINGLE_ENDED, false, true);
            checkTrue("configure enables DAC", true);

            adc.setDac(0);
            checkTrue("setDac(0) accepted", true);

            adc.setDac(255);
            checkTrue("setDac(255) accepted", true);

            adc.setDac(128);
            checkTrue("setDac(128) accepted", true);

            adc.setDacVoltage(0.0);
            checkTrue("setDacVoltage(0.0) accepted", true);

            adc.setDacVoltage(1.0);
            checkTrue("setDacVoltage(1.0) accepted", true);

            adc.setDacVoltage(0.5);
            checkTrue("setDacVoltage(0.5) accepted", true);

            adc.disableDac();
            checkTrue("disableDac accepted", true);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
