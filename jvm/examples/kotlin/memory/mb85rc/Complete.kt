///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.0+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.MB85RCFull

fun main() {
    I2CConnection(1, 0x50, null, null, 2).use { connection ->                    // open I²C bus 1, device 0x50, (bus, address, intPin, enPin, regBytes=2) → I2CConnection

        val fram = MB85RCFull(connection)                                        // construct driver, (connection) → MB85RCFull

        println("Size: ${fram.size()} bytes")                                    // get array size, () → Int
                                                                                 // always 32768 for the MB85RC256V
        fram.maxChunk = 16                                                       // set max bytes per bus transaction, (maxChunk=30) → Unit
        println("maxChunk: ${fram.maxChunk}")                                    // get max bytes per bus transaction, () → Int

        fram.writeByte(0x0010, 0xA5)                                             // write a single byte, (address 0–32767, value 0–255) → Unit
                                                                                 // no write cycle, no polling
        println("Byte: 0x%02X".format(fram.readByte(0x0010)))                    // read a single byte, (address 0–32767) → Int
                                                                                 // random read

        fram.write(0x0100, ByteArray(40) { it.toByte() })                        // sequential write, (address, data) → Unit
                                                                                 // split into maxChunk-byte transactions
        val back = fram.read(0x0100, 40)                                         // sequential read, (address, length) → ByteArray
                                                                                 // split into maxChunk-byte transactions
        println("Block length: ${back.size}")

        fram.fill(0x0100, 40, 0xFF)                                              // fill a range, (address, length, value=0) → Unit
                                                                                 // writes value over the range in chunks
        println("After fill: 0x%02X".format(fram.readByte(0x0100)))

        try {
            I2CConnection(1, 0x7C).use { idConnection ->                         // open I²C bus 1, device 0x7C, (bus, address) → I2CConnection
                val id = fram.readDeviceId(idConnection, 0x50)                   // read Device ID, (idConnection, address=0x50) → IntArray(3)
                                                                                 // reads 3 bytes via reserved slave address 0x7C
                println("Device ID: mfr=0x%03X density=0x%X product=0x%02X".format(id[0], id[1], id[2]))
            }
        } catch (e: Exception) {
            println("Device ID not readable on this bus")
        }
    }
}
