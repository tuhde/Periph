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

        val data = ByteArray(40) { it.toByte() }
        println("size %d bytes, page %d bytes".format(ee.size(), ee.pageSize()))
        ee.setMaxChunk(16)                                                                  // Set max bytes per bus transaction, (maxChunk=30) → void
                                                                                            // Arduino Wire limit: 32 bytes including the address byte
        println("maxChunk %d".format(ee.getMaxChunk()))
        ee.writeByte(0x0010, 0xA5)                                                          // Write a single byte, (address 0-255, value 0-255) → void
                                                                                            // ACK-polled until the write cycle ends
        println("byte 0x%02X".format(ee.readByte(0x0010)))
        ee.write(0x0040, data)                                                              // Write data, (address, data) → void
                                                                                            // split at 8-byte page boundaries and maxChunk, ACK-polled per chunk
        val back = ee.read(0x0040, 40)                                                      // Sequential read, (address, length) → byte[]
                                                                                            // split into maxChunk-byte transactions
        println("block %s".format(java.util.HexFormat.of().formatHex(back)))
        ee.writePage(0x00B8, java.util.Arrays.copyOf(data, 8))                             // Write within one page, (address, data) → void
                                                                                            // IllegalArgumentException if the data crosses a page boundary
        ee.fill(0x0040, 40, 0xFF)                                                           // Fill a range, (address, length, value=0) → void
                                                                                            // page-wise writes of value
        println("ready %s".format(ee.waitReady(10)))
    }
}
