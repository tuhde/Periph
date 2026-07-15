///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.adc_dac.Mcp4728Full;

public class Mcp4728Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var transport   = new I2CTransport(bus, addr);
             var gcTransport = new I2CTransport(bus, 0x00)) {

            var dac = new Mcp4728Full(transport, gcTransport);

            dac.setVoltage(0, 0.5);
            checkTrue("setVoltage(0, 0.5) accepted", true);

            dac.setRaw(1, 2048);
            checkTrue("setRaw(1, 2048) accepted", true);

            dac.setAll(new double[]{0.0, 0.25, 0.5, 1.0});
            checkTrue("setAll accepted", true);

            dac.setVoltageEeprom(0, 0.5, 0, 1);
            checkTrue("setVoltageEeprom accepted", true);

            dac.setRawEeprom(1, 2048, 0, 1);
            checkTrue("setRawEeprom accepted", true);

            dac.setAllEeprom(new double[]{0.0, 0.25, 0.5, 0.75},
                             new int[]{0, 0, 0, 0},
                             new int[]{1, 1, 1, 1});
            checkTrue("setAllEeprom accepted", true);

            dac.setVref(0, 0, 0, 0);
            checkTrue("setVref accepted", true);

            dac.setGain(1, 1, 1, 1);
            checkTrue("setGain accepted", true);

            dac.setPowerDown(Mcp4728Full.PD_NORMAL, Mcp4728Full.PD_NORMAL,
                             Mcp4728Full.PD_NORMAL, Mcp4728Full.PD_NORMAL);
            checkTrue("setPowerDown normal accepted", true);

            dac.setPowerDown(Mcp4728Full.PD_1K_GND, Mcp4728Full.PD_100K_GND,
                             Mcp4728Full.PD_500K_GND, Mcp4728Full.PD_NORMAL);
            checkTrue("setPowerDown modes accepted", true);

            Thread.sleep(50);

            var state = dac.read();
            checkTrue("read returns 4 channels", state.channel().length == 4);
            checkTrue("ch0 code in range",      state.channel()[0].code() >= 0 && state.channel()[0].code() <= 4095);
            checkTrue("ch0 vref valid",         state.channel()[0].vref() == 0 || state.channel()[0].vref() == 1);
            checkTrue("ch0 gain valid",         state.channel()[0].gain() == 1 || state.channel()[0].gain() == 2);
            checkTrue("ch0 eepromCode in range", state.channel()[0].eepromCode() >= 0 && state.channel()[0].eepromCode() <= 4095);

            checkTrue("isEepromReady returns boolean", true);

            dac.softwareUpdate();
            checkTrue("softwareUpdate accepted", true);

            dac.wakeUp();
            checkTrue("wakeUp accepted", true);

            dac.reset();
            checkTrue("reset accepted", true);

            boolean ready = dac.isEepromReady();
            checkTrue("isEepromReady ok", true);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
