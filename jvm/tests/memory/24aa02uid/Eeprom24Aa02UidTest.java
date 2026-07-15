///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.memory.Eeprom24Aa02UidFull;

public class Eeprom24Aa02UidTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static void checkEqBytes(String label, byte[] got, byte[] expected) {
        if (got.length != expected.length) {
            System.out.println("FAIL " + label + ": got len " + got.length + ", expected " + expected.length);
            failed++;
            return;
        }
        for (int i = 0; i < got.length; i++) {
            if (got[i] != expected[i]) {
                System.out.println("FAIL " + label + ": byte " + i + " got 0x" + String.format("%02X", got[i] & 0xFF) +
                                   ", expected 0x" + String.format("%02X", expected[i] & 0xFF));
                failed++;
                return;
            }
        }
        System.out.println("PASS " + label);
        passed++;
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x50").replaceFirst("^0[xX]", ""), 16);

        try (var transport = new I2CTransport(bus, addr)) {

            var eeprom = new Eeprom24Aa02UidFull(transport);

            byte[] uid = eeprom.readUid();
            checkTrue("readUid length 4", uid.length == 4);
            checkTrue("readManufacturerCode 0x29", eeprom.readManufacturerCode() == 0x29);
            checkTrue("readDeviceCode 0x41",       eeprom.readDeviceCode()       == 0x41);

            final int TEST_ADDRESS = 0x10;
            final int TEST_VALUE   = 0x5A;
            eeprom.writeByte(TEST_ADDRESS, TEST_VALUE);
            checkTrue("writeByte/readByte round-trip", eeprom.readByte(TEST_ADDRESS) == TEST_VALUE);

            final byte[] PAGE_DATA = new byte[] { 0x11, 0x22, 0x33, 0x44 };
            eeprom.writePage(0x40, PAGE_DATA);
            checkEqBytes("writePage read-back", eeprom.read(0x40, PAGE_DATA.length), PAGE_DATA);

            final byte[] CROSS_DATA = new byte[] { (byte)0xAA, (byte)0xBB, (byte)0xCC, (byte)0xDD, (byte)0xEE, (byte)0xFF };
            eeprom.write(0x06, CROSS_DATA);
            checkEqBytes("cross-page write read-back", eeprom.read(0x06, CROSS_DATA.length), CROSS_DATA);

            byte[] rangeRead = eeprom.read(0x50, 16);
            checkTrue("sequential read length 16", rangeRead.length == 16);

            checkEqBytes("uid unchanged after writes", eeprom.readUid(), uid);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
