///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.memory.MB85RCFull;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x50, null, null, 2)) {   // open I²C bus 1, device 0x50, (bus, address, intPin, enPin, regBytes=2) → I2CConnection
            var fram = new MB85RCFull(connection);                           // construct driver, (connection) → MB85RCFull

            // --- Persistent boot counter ---
            // A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has
            // no write cycle, so the increment is committed when the write ends.
            byte[] c = fram.read(0x0000, 4);                                 // sequential read, (address, length) → byte[]
            long counter = (((long) (c[0] & 0xFF) << 24) | ((c[1] & 0xFF) << 16) | ((c[2] & 0xFF) << 8) | (c[3] & 0xFF)) + 1;
            fram.write(0x0000, new byte[]{                                   // sequential write, (address, data) → void
                (byte) (counter >> 24), (byte) (counter >> 16), (byte) (counter >> 8), (byte) counter });
            System.out.println("Boot count: " + counter);

            // --- Ring-buffer logger, back-to-back with no delays ---
            // 1,000 records of 16 bytes are written without any pause; an EEPROM
            // would need ACK polling or delays after every page, FRAM does not.
            final int base = 0x0100, record = 16, count = 1000, ring = 200;
            long t0 = System.nanoTime();
            for (int i = 0; i < count; i++) {
                byte[] rec = new byte[record];
                rec[0] = (byte) (i >> 24); rec[1] = (byte) (i >> 16); rec[2] = (byte) (i >> 8); rec[3] = (byte) i;
                rec[4] = (byte) (counter >> 24); rec[5] = (byte) (counter >> 16); rec[6] = (byte) (counter >> 8); rec[7] = (byte) counter;
                fram.write(base + (i % ring) * record, rec);                 // sequential write, (address, data) → void
            }
            double dt = (System.nanoTime() - t0) / 1e9;
            System.out.printf("Logged %d bytes in %.3f s = %d B/s%n", count * record, dt, (long) (count * record / dt));

            // --- Verify the last five records ---
            // Read the newest records back from the ring and check their index.
            boolean ok = true;
            for (int i = count - 5; i < count; i++) {
                byte[] rec = fram.read(base + (i % ring) * record, record);  // sequential read, (address, length) → byte[]
                int idx = ((rec[0] & 0xFF) << 24) | ((rec[1] & 0xFF) << 16) | ((rec[2] & 0xFF) << 8) | (rec[3] & 0xFF);
                if (idx != i) ok = false;
            }
            System.out.println("Last 5 records verified: " + ok);

            // --- Clear the log area ---
            // fill() zeroes the whole ring in chunked transactions.
            fram.fill(base, ring * record, 0);                               // fill a range, (address, length, value=0) → void
            System.out.println("Log cleared; array size: " + fram.size() + " bytes");   // get array size, () → int

            // --- Device ID (needs the reserved address 0x7C) ---
            // Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F; skip then.
            try (var idConnection = new I2CConnection(1, 0x7C)) {            // open I²C bus 1, device 0x7C, (bus, address) → I2CConnection
                int[] id = fram.readDeviceId(idConnection, 0x50);            // read Device ID, (idConnection, address=0x50) → int[3]
                System.out.printf("Device ID: 0x%03X 0x%X 0x%02X%n", id[0], id[1], id[2]);
            } catch (Exception e) {
                System.out.println("Device ID not available");
            }
        }
    }
}
