///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.display.Pcf8576Minimal;
import it.uhde.periph.chips.display.Pcf8576Full;

public class Pcf8576Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));

        try (var transport = new I2CTransport(bus, addr)) {

            var lcd = new Pcf8576Full(transport);

            checkTrue("cmdMode off == 0x40",
                lcd.cmdMode(false, Pcf8576Minimal.BIAS_1_3, Pcf8576Minimal.MODE_1_4) == 0x40);
            checkTrue("cmdMode on == 0x48",
                lcd.cmdMode(true, Pcf8576Minimal.BIAS_1_3, Pcf8576Minimal.MODE_1_4) == 0x48);
            checkTrue("cmdMode static == 0x49",
                lcd.cmdMode(true, Pcf8576Minimal.BIAS_1_3, Pcf8576Minimal.MODE_STATIC) == 0x49);
            checkTrue("cmdMode 1/2 bias == 0x4C",
                lcd.cmdMode(true, Pcf8576Minimal.BIAS_1_2, Pcf8576Minimal.MODE_1_4) == 0x4C);
            checkTrue("seven_seg_0 == 0xED", Pcf8576Minimal.SEVEN_SEG[0] == 0xED);
            checkTrue("seven_seg_9 == 0xEB", Pcf8576Minimal.SEVEN_SEG[9] == 0xEB);

            lcd.clear();
            checkTrue("clear", true);

            lcd.setDigit7seg(0, 0xED);
            lcd.setDigit7seg(1, 0x60);
            checkTrue("set_digit_7seg", true);

            byte[] bytes = {(byte) 0xED, (byte) 0x60, (byte) 0xA7, (byte) 0xE3};
            lcd.writeRaw(0, bytes);
            checkTrue("write_raw", true);

            lcd.enable();
            lcd.disable();
            lcd.enable();
            checkTrue("enable_disable", true);

            lcd.setMode(Pcf8576Full.BACKPLANES_4, Pcf8576Full.BIAS_1_3_FULL);
            lcd.setMode(Pcf8576Full.BACKPLANES_2, Pcf8576Full.BIAS_1_2_FULL);
            lcd.setMode(Pcf8576Full.BACKPLANES_1, Pcf8576Full.BIAS_1_3_FULL);
            checkTrue("set_mode", true);

            lcd.setBlink(Pcf8576Full.BLINK_2_HZ, false);
            lcd.setBlink(Pcf8576Full.BLINK_OFF, true);
            checkTrue("set_blink", true);

            lcd.setBank(0, 0);
            lcd.setBank(1, 1);
            checkTrue("set_bank", true);

            lcd.deviceSelect(0);
            lcd.deviceSelect(7);
            checkTrue("device_select", true);

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
