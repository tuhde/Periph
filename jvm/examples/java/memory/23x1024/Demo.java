///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.memory.Sram23x1024Full;

import java.util.Arrays;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var sram = new Sram23x1024Full(connection);                           // Create 23X1024 Full driver, (connection) → Sram23x1024Full

            // --- Store a 256-entry lookup table in external SRAM ---
            // A sine table is the kind of data a microcontroller with little RAM
            // keeps outside: written once, then read back entry by entry.
            byte[] table = new byte[256];
            for (int i = 0; i < table.length; i++) table[i] = (byte) (127.5 + 127.5 * Math.sin(2 * Math.PI * i / 256));
            sram.write(0x00000, table);                                           // Block write, (address, data) → void
            byte[] check = sram.read(0x00000, table.length);                      // Block read, (address, length) → byte[]
            System.out.printf("sine table: %s (sin(64/256 turn) = %d)%n",
                    Arrays.equals(check, table) ? "ok" : "MISMATCH", sram.readByte(64));   // Read one byte, (address) → int

            // --- Wipe the whole array and spot-check it ---
            // Zero write time means the 128 KiB fill runs at SPI speed with no
            // waiting; the first, middle and last byte prove the full range.
            sram.fill(0x00000, sram.size(), 0xA5);                                // Fill a range, (address, length, value) → void
            System.out.printf("fill spot check: 0x%02X 0x%02X 0x%02X%n",
                    sram.readByte(0x00000), sram.readByte(0x10000), sram.readByte(0x1FFFF));   // Read one byte, (address) → int

            // --- Page mode: a write that crosses a 32-byte page boundary ---
            // The hardware would wrap inside the page; the driver splits the
            // transfer so the 64 bytes starting at address 16 still read back in order.
            sram.setMode(Sram23x1024Full.MODE_PAGE);                              // Select operating mode, (mode=MODE_PAGE=0x80) → void
            byte[] ramp = new byte[64];
            for (int i = 0; i < ramp.length; i++) ramp[i] = (byte) (0x40 + i);
            sram.write(16, ramp);                                                 // Block write, (address, data) → void
            byte[] rampBack = sram.read(16, ramp.length);                         // Block read, (address, length) → byte[]
            System.out.println("page-crossing write: " + (Arrays.equals(rampBack, ramp) ? "ok" : "MISMATCH"));
            sram.setMode(Sram23x1024Full.MODE_SEQUENTIAL);                        // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → void

            // --- Measure throughput for a 4 KiB transfer ---
            // Sequential mode streams the block in 256-byte transactions;
            // System.nanoTime() times them.
            byte[] block = new byte[4096];
            for (int i = 0; i < block.length; i++) block[i] = (byte) i;
            long t0 = System.nanoTime();
            sram.write(0x01000, block);                                           // Block write, (address, data) → void
            long t1 = System.nanoTime();
            sram.read(0x01000, block.length);                                     // Block read, (address, length) → byte[]
            long t2 = System.nanoTime();
            System.out.printf("4 KiB write: %.1f ms, read: %.1f ms%n", (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        }
    }
}
