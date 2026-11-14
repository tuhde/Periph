///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.memory.AT24C64Full;

public class Demo {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x50").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr, null, null, 2)) {
            var ee = new AT24C64Full(connection);                                         // Create AT24C64 driver, (connection) → void

            // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
            byte[] cb = ee.read(0x0000, 4);                                                 // Sequential read, (address, length) → byte[]
            int counter = java.nio.ByteBuffer.wrap(cb).getInt() + 1;
            byte[] cbOut = java.nio.ByteBuffer.allocate(4).putInt(counter).array();
            ee.write(0x0000, cbOut);                                                        // Write data, (address, data) → void
            System.out.printf("Boot counter: %d%n", counter);
            // --- Page-write benchmark: 3 pages, 32-byte aligned ---
            byte[] pattern = new byte[96];
            for (int i = 0; i < 96; i++) pattern[i] = (byte) (i * 7);
            long t0 = System.nanoTime();
            ee.write(0x0080, pattern);                                                      // Write data, (address, data) → void
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf("Wrote %d bytes in %.1f ms (incl. ACK-polled write cycles)%n", pattern.length, ms);
            System.out.printf("Read-back matches: %s%n", java.util.Arrays.equals(ee.read(0x0080, 96), pattern));
            // --- A write that crosses a page boundary is refused ---
            try {
                ee.writePage(0x00FC, new byte[8]);                                                  // Write within one page, (address, data) → void
            } catch (IllegalArgumentException e) {
                System.out.println("writePage refused: " + e.getMessage());
            }
            ee.fill(0x0080, 96);                                                           // Fill a range, (address, length, value=0) → void
            System.out.printf("size %d, page %d, ready %s%n", ee.size(), ee.pageSize(), ee.waitReady(10));
        }
    }
}
