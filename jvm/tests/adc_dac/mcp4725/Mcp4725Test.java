///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.adc_dac.Mcp4725Full;

public class Mcp4725Test {

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

            var dac = new Mcp4725Full(transport, gcTransport);

            dac.setVoltage(0.5);
            checkTrue("setVoltage(0.5) accepted", true);

            dac.setRaw(2048);
            checkTrue("setRaw(2048) accepted", true);

            dac.setVoltageEeprom(0.5);
            checkTrue("setVoltageEeprom(0.5) accepted", true);

            dac.setRawEeprom(2048);
            checkTrue("setRawEeprom(2048) accepted", true);

            Thread.sleep(50);

            var state = dac.read();
            checkTrue("read: code in range",            state.code() >= 0 && state.code() <= 4095);
            checkTrue("read: voltageFraction in range", state.voltageFraction() >= 0.0 && state.voltageFraction() <= 1.0);
            checkTrue("read: powerDown in range",       state.powerDown() >= 0 && state.powerDown() <= 3);
            checkTrue("read: eepromCode in range",      state.eepromCode() >= 0 && state.eepromCode() <= 4095);
            checkTrue("read: eepromPowerDown in range", state.eepromPowerDown() >= 0 && state.eepromPowerDown() <= 3);

            dac.setPowerDown(0);
            checkTrue("setPowerDown(0 normal) accepted", true);

            dac.setPowerDown(1);
            checkTrue("setPowerDown(1 1kΩ) accepted", true);

            dac.setPowerDown(2);
            checkTrue("setPowerDown(2 100kΩ) accepted", true);

            dac.setPowerDown(3);
            checkTrue("setPowerDown(3 500kΩ) accepted", true);

            dac.wakeUp();
            checkTrue("wakeUp accepted", true);

            dac.reset();
            checkTrue("reset accepted", true);

            dac.isEepromReady();
            checkTrue("isEepromReady accepted", true);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
