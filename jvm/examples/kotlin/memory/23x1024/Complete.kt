///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.memory.Sram23x1024Full

fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(bus, dev, 0, 8_000_000).use { connection ->                    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
        val sram = Sram23x1024Full(connection)                                   // Create 23X1024 Full driver, (connection) → Sram23x1024Full
                                                                                 // writes MODE=0x40 (Sequential) and verifies it with RDMR

        println("size: ${sram.size()} bytes")                                    // Array size, () → Int bytes
                                                                                 // always 131072

        sram.writeByte(0x00010, 0xA5)                                            // Write one byte, (address 0–131071, value 0–255) → Unit
                                                                                 // WRITE 0x02 + 24-bit address + data
        println("byte @0x10: 0x%02X".format(sram.readByte(0x00010)))             // Read one byte, (address 0–131071) → Int
                                                                                 // READ 0x03 + 24-bit address

        val pattern = ByteArray(300) { it.toByte() }
        sram.write(0x00100, pattern)                                             // Block write, (address, data) → Unit
                                                                                 // split into transactions of at most maxChunk() bytes
        val readback = sram.read(0x00100, pattern.size)                          // Block read, (address, length) → ByteArray
                                                                                 // sequential read across the chunk boundary
        println("block of 300: " + if (readback.contentEquals(pattern)) "match" else "MISMATCH")

        sram.fill(0x00200, 64, 0xFF)                                             // Fill a range, (address, length, value) → Unit
                                                                                 // writes value over the range, chunked
        println("fill @0x200: 0x%02X".format(sram.readByte(0x00200)))

        sram.setMaxChunk(64)                                                     // Limit bytes per transaction, (n 1–256) → Unit
                                                                                 // smaller frames for bus layers with small buffers
        println("max_chunk: ${sram.maxChunk()}")                                 // Current chunk limit, () → Int bytes
        sram.setMaxChunk(256)                                                    // Limit bytes per transaction, (n 1–256) → Unit

        sram.setMode(Sram23x1024Full.MODE_PAGE)                                  // Select operating mode, (mode=MODE_PAGE=0x80) → Unit
                                                                                 // WRMR then RDMR check; accesses now wrap within 32-byte pages
        println("mode: 0x%02X".format(sram.getMode()))                           // Read operating mode, () → Int
                                                                                 // RDMR bits 7:6
        sram.write(0x00110, pattern.copyOf(64))                                  // Block write, (address, data) → Unit
                                                                                 // the driver splits at page boundaries, so the data stays linear
        val paged = sram.read(0x00110, 64)                                       // Block read, (address, length) → ByteArray
        println("page mode 64 bytes: " + if (paged.contentEquals(pattern.copyOf(64))) "match" else "MISMATCH")

        sram.setMode(Sram23x1024Full.MODE_SEQUENTIAL)                            // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → Unit
        sram.resetIo()                                                           // Return to SPI mode, () → Unit
                                                                                 // sends RSTIO (0xFF); harmless while already in SPI mode
    }
}
