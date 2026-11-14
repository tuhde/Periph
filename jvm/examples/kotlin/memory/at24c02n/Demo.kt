///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.AT24C02NFull
import java.util.function.IntConsumer

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x50").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val ee = AT24C02NFull(connection)                                                  // Create AT24C02N driver, (connection) → void

        // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
        val cb = ee.read(0x0000, 4)                                                         // Sequential read, (address, length) → byte[]
        val counter = java.nio.ByteBuffer.wrap(cb).getInt() + 1
        val cbOut = java.nio.ByteBuffer.allocate(4).putInt(counter).array()
        ee.write(0x0000, cbOut)                                                             // Write data, (address, data) → void
        println("Boot counter: %d".format(counter))
        // --- Page-write benchmark: 3 pages, 8-byte aligned ---
        val pattern = ByteArray(24) { (it * 7).toByte() }
        val t0 = System.nanoTime()
        ee.write(0x0080, pattern)                                                           // Write data, (address, data) → void
        val ms = (System.nanoTime() - t0) / 1e6
        println("Wrote %d bytes in %.1f ms (incl. ACK-polled write cycles)".format(pattern.length, ms))
        println("Read-back matches: %s".format(java.util.Arrays.equals(ee.read(0x0080, 24), pattern)))
        // --- A write that crosses a page boundary is refused ---
        try {
            ee.writePage(0x00F4, ByteArray(8))                                                  // Write within one page, (address, data) → void
        } catch (e: IllegalArgumentException) {
            println("writePage refused: " + e.message)
        }
        ee.fill(0x0080, 24)                                                                // Fill a range, (address, length, value=0) → void
        println("size %d, page %d, ready %s".format(ee.size(), ee.pageSize(), ee.waitReady(10)))
    }
}
