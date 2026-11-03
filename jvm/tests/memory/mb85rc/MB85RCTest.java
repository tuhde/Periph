///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.memory.MB85RCFull;

public class MB85RCTest {

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

        try (var connection = new I2CConnection(bus, addr, null, null, 2)) {
            var fram = new MB85RCFull(connection);

            checkTrue("size 32768", fram.size() == 32768);

            fram.writeByte(0x0010, 0x5A);
            checkTrue("writeByte/readByte round-trip", fram.readByte(0x0010) == 0x5A);

            byte[] data = new byte[100];
            for (int i = 0; i < data.length; i++) data[i] = (byte) i;
            fram.write(0x0100, data);
            checkEqBytes("chunked write/read round-trip", fram.read(0x0100, data.length), data);

            byte[] c3 = new byte[100];
            java.util.Arrays.fill(c3, (byte) 0xC3);
            fram.fill(0x0100, 100, 0xC3);
            checkEqBytes("fill", fram.read(0x0100, 100), c3);
            fram.fill(0x0100, 100);
            checkEqBytes("fill default zero", fram.read(0x0100, 100), new byte[100]);

            fram.write(0x7FFE, new byte[]{0x12, 0x34});
            checkEqBytes("last bytes round-trip", fram.read(0x7FFE, 2), new byte[]{0x12, 0x34});

            try {
                fram.read(0x7FFF, 2);
                checkTrue("wrap rejected", false);
            } catch (IllegalArgumentException e) {
                checkTrue("wrap rejected", true);
            }

            try (var idConnection = new I2CConnection(bus, 0x7C)) {
                int[] id = fram.readDeviceId(idConnection, addr);
                checkTrue("readDeviceId 0x00A/0x5/0x10", id[0] == 0x00A && id[1] == 0x5 && id[2] == 0x10);
            } catch (Exception e) {
                System.out.println("SKIP readDeviceId (0x7C not accessible on this host)");
            }
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
