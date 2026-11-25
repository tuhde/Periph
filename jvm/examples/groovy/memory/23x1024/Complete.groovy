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
                                                                                // writes MODE=0x40 (Sequential) and verifies it with RDMR

    println "size: ${sram.size()} bytes"                                        // Array size, () → int bytes
                                                                                // always 131072

    sram.writeByte(0x00010, 0xA5)                                               // Write one byte, (address 0–131071, value 0–255) → void
                                                                                // WRITE 0x02 + 24-bit address + data
    println String.format("byte @0x10: 0x%02X", sram.readByte(0x00010))        // Read one byte, (address 0–131071) → int
                                                                                // READ 0x03 + 24-bit address

    byte[] pattern = new byte[300]
    for (int i = 0; i < pattern.length; i++) pattern[i] = (byte) i
    sram.write(0x00100, pattern)                                                // Block write, (address, data) → void
                                                                                // split into transactions of at most maxChunk() bytes
    byte[] readback = sram.read(0x00100, pattern.length)                        // Block read, (address, length) → byte[]
                                                                                // sequential read across the chunk boundary
    println "block of 300: ${Arrays.equals(readback, pattern) ? 'match' : 'MISMATCH'}"

    sram.fill(0x00200, 64, 0xFF)                                                // Fill a range, (address, length, value) → void
                                                                                // writes value over the range, chunked
    println String.format("fill @0x200: 0x%02X", sram.readByte(0x00200))

    sram.setMaxChunk(64)                                                        // Limit bytes per transaction, (n 1–256) → void
                                                                                // smaller frames for bus layers with small buffers
    println "max_chunk: ${sram.maxChunk()}"                                     // Current chunk limit, () → int bytes
    sram.setMaxChunk(256)                                                       // Limit bytes per transaction, (n 1–256) → void

    sram.setMode(Sram23x1024Full.MODE_PAGE)                                     // Select operating mode, (mode=MODE_PAGE=0x80) → void
                                                                                // WRMR then RDMR check; accesses now wrap within 32-byte pages
    println String.format("mode: 0x%02X", sram.getMode())                       // Read operating mode, () → int
                                                                                // RDMR bits 7:6
    sram.write(0x00110, Arrays.copyOf(pattern, 64))                             // Block write, (address, data) → void
                                                                                // the driver splits at page boundaries, so the data stays linear
    byte[] paged = sram.read(0x00110, 64)                                       // Block read, (address, length) → byte[]
    println "page mode 64 bytes: ${Arrays.equals(paged, Arrays.copyOf(pattern, 64)) ? 'match' : 'MISMATCH'}"

    sram.setMode(Sram23x1024Full.MODE_SEQUENTIAL)                               // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → void
    sram.resetIo()                                                              // Return to SPI mode, () → void
                                                                                // sends RSTIO (0xFF); harmless while already in SPI mode
}
