///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.memory.Sram23x1024Full

int bus = System.getenv("SPI_BUS")?.toInteger() ?: 0
int dev = System.getenv("SPI_DEVICE")?.toInteger() ?: 0

try (SPIConnection connection = new SPIConnection(bus, dev, 0, 8_000_000)) {    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
    Sram23x1024Full sram = new Sram23x1024Full(connection)                     // Create 23X1024 Full driver, (connection) → Sram23x1024Full

    // --- Store a 256-entry lookup table in external SRAM ---
    // A sine table is the kind of data a microcontroller with little RAM
    // keeps outside: written once, then read back entry by entry.
    byte[] table = new byte[256]
    for (int i = 0; i < table.length; i++) table[i] = (byte) (127.5d + 127.5d * Math.sin(2 * Math.PI * i / 256))
    sram.write(0x00000, table)                                                  // Block write, (address, data) → void
    byte[] check = sram.read(0x00000, table.length)                             // Block read, (address, length) → byte[]
    println "sine table: ${Arrays.equals(check, table) ? 'ok' : 'MISMATCH'} (sin(64/256 turn) = ${sram.readByte(64)})"   // Read one byte, (address) → int

    // --- Wipe the whole array and spot-check it ---
    // Zero write time means the 128 KiB fill runs at SPI speed with no
    // waiting; the first, middle and last byte prove the full range.
    sram.fill(0x00000, sram.size(), 0xA5)                                       // Fill a range, (address, length, value) → void
    println String.format("fill spot check: 0x%02X 0x%02X 0x%02X",
            sram.readByte(0x00000), sram.readByte(0x10000), sram.readByte(0x1FFFF))   // Read one byte, (address) → int

    // --- Page mode: a write that crosses a 32-byte page boundary ---
    // The hardware would wrap inside the page; the driver splits the
    // transfer so the 64 bytes starting at address 16 still read back in order.
    sram.setMode(Sram23x1024Full.MODE_PAGE)                                     // Select operating mode, (mode=MODE_PAGE=0x80) → void
    byte[] ramp = new byte[64]
    for (int i = 0; i < ramp.length; i++) ramp[i] = (byte) (0x40 + i)
    sram.write(16, ramp)                                                        // Block write, (address, data) → void
    byte[] rampBack = sram.read(16, ramp.length)                                // Block read, (address, length) → byte[]
    println "page-crossing write: ${Arrays.equals(rampBack, ramp) ? 'ok' : 'MISMATCH'}"
    sram.setMode(Sram23x1024Full.MODE_SEQUENTIAL)                               // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → void

    // --- Measure throughput for a 4 KiB transfer ---
    // Sequential mode streams the block in 256-byte transactions;
    // System.nanoTime() times them.
    byte[] block = new byte[4096]
    for (int i = 0; i < block.length; i++) block[i] = (byte) i
    long t0 = System.nanoTime()
    sram.write(0x01000, block)                                                  // Block write, (address, data) → void
    long t1 = System.nanoTime()
    sram.read(0x01000, block.length)                                            // Block read, (address, length) → byte[]
    long t2 = System.nanoTime()
    println String.format("4 KiB write: %.1f ms, read: %.1f ms", (t1 - t0) / 1e6d, (t2 - t1) / 1e6d)
}
