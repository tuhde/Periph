///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.0+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.memory.Eeprom24Aa02UidFull

fun main() {
    I2CTransport(1, 0x50).use { transport ->                                    // open I²C bus 1, device 0x50, (bus, address) → I2CTransport

        val eeprom = Eeprom24Aa02UidFull(transport)                             // construct driver, (transport) → Eeprom24Aa02UidFull

        val uid = eeprom.readUid()                                               // read 32-bit unique serial number, () → ByteArray
                                                                                  // reads 4 bytes at 0xFC-0xFF
        val uidHex = uid.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        println("UID bytes: $uidHex")

        val uidInt = ((uid[0].toLong() and 0xFF) shl 24) or ((uid[1].toLong() and 0xFF) shl 16)
                  or ((uid[2].toLong() and 0xFF) shl 8)  or  (uid[3].toLong() and 0xFF)
        println("UID int:   $uidInt")

        val mfr = eeprom.readManufacturerCode()                                   // read manufacturer code, () → Int
                                                                                  // reads 0xFA; expect 0x29 (Microchip)
        val dev = eeprom.readDeviceCode()                                         // read device code, () → Int
                                                                                  // reads 0xFB; expect 0x41
        println("MFR: 0x%02X  DEV: 0x%02X".format(mfr, dev))

        val first = eeprom.readByte(0x00)                                         // read a single byte, (address=0x00-0x7F) → Int
                                                                                  // random read at user EEPROM address
        println("First byte: 0x%02X".format(first))

        eeprom.writeByte(0x10, 0xA5)                                              // write a single byte, (address, value) → Unit
                                                                                  // byte write + delay until complete (max 5 ms)
        val verify = eeprom.readByte(0x10)                                       // read a single byte, (address=0x00-0x7F) → Int
        println("Wrote 0xA5, read back: 0x%02X".format(verify))

        val block = eeprom.read(0x20, 8)                                          // sequential read, (address, length) → ByteArray
                                                                                  // reads 8 bytes starting at address
        print("Block @ 0x20:")
        for (b in block) { print(" %02X".format(b.toInt() and 0xFF)) }
        println()

        val page = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        eeprom.writePage(0x40, page)                                              // page write, (address, data) → Unit
                                                                                  // writes up to 8 bytes within one page

        val cross = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
        eeprom.write(0x44, cross)                                                 // arbitrary-length write, (address, data) → Unit
                                                                                  // splits at 8-byte page boundaries; waits for each chunk
        println("Multi-page write complete")
    }
}
