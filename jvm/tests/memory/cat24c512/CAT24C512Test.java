///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.memory.CAT24C512Full;

import java.util.Arrays;

public class CAT24C512Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x50").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr, null, null, 2)) {
            var ee = new CAT24C512Full(connection);

            checkTrue("size", ee.size() == 65536);
            checkTrue("page_size", ee.pageSize() == 128);

            ee.writeByte(0x0010, 0x5A);
            checkTrue("writeByte/readByte round-trip", ee.readByte(0x0010) == 0x5A);
            checkTrue("waitReady after write", ee.waitReady(10));

            byte[] data = new byte[200];
            for (int i = 0; i < 200; i++) data[i] = (byte) i;
            ee.write(0x0070, data);
            checkTrue("page-split write/read round-trip", Arrays.equals(ee.read(0x0070, 200), data));

            byte[] page = new byte[128];
            for (int i = 0; i < 128; i++) page[i] = (byte) i;
            ee.writePage(0x0300, page);
            checkTrue("writePage round-trip", Arrays.equals(ee.read(0x0300, 128), page));
            boolean rejected = false;
            try { ee.writePage(0x037C, new byte[8]); } catch (IllegalArgumentException e) { rejected = true; }
            checkTrue("writePage crossing rejected", rejected);

            byte[] c3 = new byte[100];
            Arrays.fill(c3, (byte) 0xC3);
            ee.fill(0x0100, 100, 0xC3);
            checkTrue("fill", Arrays.equals(ee.read(0x0100, 100), c3));
            ee.fill(0x0100, 100);
            checkTrue("fill default zero", Arrays.equals(ee.read(0x0100, 100), new byte[100]));

            ee.write(0xFFFE, new byte[]{0x12, 0x34});
            checkTrue("last bytes round-trip", Arrays.equals(ee.read(0xFFFE, 2), new byte[]{0x12, 0x34}));
            rejected = false;
            try { ee.read(0xFFFF, 2); } catch (IllegalArgumentException e) { rejected = true; }
            checkTrue("wrap rejected", rejected);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
