///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.AT24C64Full
import java.util.function.IntConsumer

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x50").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr, null, null, 2)
try {
    def ee = new AT24C64Full(conn)                                                        // Create AT24C64 driver, (connection) → void

    byte[] data = new byte[40]
    for (int i = 0; i < 40; i++) data[i] = (byte) i
    printf("size %d bytes, page %d bytes%n", ee.size(), ee.pageSize())
    ee.setMaxChunk(16)                                                                      // Set max bytes per bus transaction, (maxChunk=30) → void
                                                                                            // Arduino Wire limit: 32 bytes including the 2 address bytes
    printf("maxChunk %d%n", ee.getMaxChunk())
    ee.writeByte(0x0010, 0xA5)                                                              // Write a single byte, (address 0-8191, value 0-255) → void
                                                                                            // ACK-polled until the write cycle ends
    printf("byte 0x%02X%n", ee.readByte(0x0010))
    ee.write(0x0100, data)                                                                  // Write data, (address, data) → void
                                                                                            // split at 32-byte page boundaries and maxChunk, ACK-polled per chunk
    def back = ee.read(0x0100, 40)                                                          // Sequential read, (address, length) → byte[]
                                                                                            // split into maxChunk-byte transactions
    printf("block %s%n", java.util.HexFormat.of().formatHex(back))
    ee.writePage(0x0180, java.util.Arrays.copyOf(data, 16))                                 // Write within one page, (address, data) → void
                                                                                            // IllegalArgumentException if the data crosses a page boundary
    ee.fill(0x0100, 40, 0xFF)                                                               // Fill a range, (address, length, value=0) → void
                                                                                            // page-wise writes of value
    printf("ready %s%n", ee.waitReady(10))
} finally {
    conn.close()
}
