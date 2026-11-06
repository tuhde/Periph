///usr/bin/env jbang "$0" "$@" ; exit $?
//GROOVY 4
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.MB85RCFull

def connection = new I2CConnection(1, 0x50, null, null, 2)                        // open I²C bus 1, device 0x50, (bus, address, intPin, enPin, regBytes=2) → I2CConnection
try {
    def fram = new MB85RCFull(connection)                                         // construct driver, (connection) → MB85RCFull

    println("Size: ${fram.size()} bytes")                                         // get array size, () → int
                                                                                  // always 32768 for the MB85RC256V
    fram.maxChunk = 16                                                            // set max bytes per bus transaction, (maxChunk=30) → void
    println("maxChunk: ${fram.maxChunk}")                                         // get max bytes per bus transaction, () → int

    fram.writeByte(0x0010, 0xA5)                                                  // write a single byte, (address 0–32767, value 0–255) → void
                                                                                  // no write cycle, no polling
    println(String.format('Byte: 0x%02X', fram.readByte(0x0010)))                 // read a single byte, (address 0–32767) → int
                                                                                  // random read

    byte[] data = (0..<40).collect { (byte) it } as byte[]
    fram.write(0x0100, data)                                                      // sequential write, (address, data) → void
                                                                                  // split into maxChunk-byte transactions
    def back = fram.read(0x0100, 40)                                              // sequential read, (address, length) → byte[]
                                                                                  // split into maxChunk-byte transactions
    println("Block length: ${back.length}")

    fram.fill(0x0100, 40, 0xFF)                                                   // fill a range, (address, length, value=0) → void
                                                                                  // writes value over the range in chunks
    println(String.format('After fill: 0x%02X', fram.readByte(0x0100)))

    try {
        def idConnection = new I2CConnection(1, 0x7C)                             // open I²C bus 1, device 0x7C, (bus, address) → I2CConnection
        try {
            def id = fram.readDeviceId(idConnection, 0x50)                        // read Device ID, (idConnection, address=0x50) → int[3]
                                                                                  // reads 3 bytes via reserved slave address 0x7C
            println(String.format('Device ID: mfr=0x%03X density=0x%X product=0x%02X', id[0], id[1], id[2]))
        } finally {
            idConnection.close()
        }
    } catch (Exception e) {
        println('Device ID not readable on this bus')
    }
} finally {
    connection.close()
}
