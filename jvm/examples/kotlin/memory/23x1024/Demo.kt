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

        // --- Store a 256-entry lookup table in external SRAM ---
        // A sine table is the kind of data a microcontroller with little RAM
        // keeps outside: written once, then read back entry by entry.
        val table = ByteArray(256) { (127.5 + 127.5 * Math.sin(2 * Math.PI * it / 256)).toInt().toByte() }
        sram.write(0x00000, table)                                               // Block write, (address, data) → Unit
        val check = sram.read(0x00000, table.size)                               // Block read, (address, length) → ByteArray
        println("sine table: ${if (check.contentEquals(table)) "ok" else "MISMATCH"} (sin(64/256 turn) = ${sram.readByte(64)})")   // Read one byte, (address) → Int

        // --- Wipe the whole array and spot-check it ---
        // Zero write time means the 128 KiB fill runs at SPI speed with no
        // waiting; the first, middle and last byte prove the full range.
        sram.fill(0x00000, sram.size(), 0xA5)                                    // Fill a range, (address, length, value) → Unit
        println("fill spot check: 0x%02X 0x%02X 0x%02X".format(
            sram.readByte(0x00000), sram.readByte(0x10000), sram.readByte(0x1FFFF)))   // Read one byte, (address) → Int

        // --- Page mode: a write that crosses a 32-byte page boundary ---
        // The hardware would wrap inside the page; the driver splits the
        // transfer so the 64 bytes starting at address 16 still read back in order.
        sram.setMode(Sram23x1024Full.MODE_PAGE)                                  // Select operating mode, (mode=MODE_PAGE=0x80) → Unit
        val ramp = ByteArray(64) { (0x40 + it).toByte() }
        sram.write(16, ramp)                                                     // Block write, (address, data) → Unit
        val rampBack = sram.read(16, ramp.size)                                  // Block read, (address, length) → ByteArray
        println("page-crossing write: " + if (rampBack.contentEquals(ramp)) "ok" else "MISMATCH")
        sram.setMode(Sram23x1024Full.MODE_SEQUENTIAL)                            // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → Unit

        // --- Measure throughput for a 4 KiB transfer ---
        // Sequential mode streams the block in 256-byte transactions;
        // System.nanoTime() times them.
        val block = ByteArray(4096) { it.toByte() }
        val t0 = System.nanoTime()
        sram.write(0x01000, block)                                               // Block write, (address, data) → Unit
        val t1 = System.nanoTime()
        sram.read(0x01000, block.size)                                           // Block read, (address, length) → ByteArray
        val t2 = System.nanoTime()
        println("4 KiB write: %.1f ms, read: %.1f ms".format((t1 - t0) / 1e6, (t2 - t1) / 1e6))
    }
}
