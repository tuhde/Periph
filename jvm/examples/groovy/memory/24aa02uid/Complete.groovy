///usr/bin/env jbang "$0" "$@" ; exit $?
//GROOVY 4
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.memory.Eeprom24Aa02UidFull

def transport = new I2CTransport(1, (byte) 0x50)                                // open I²C bus 1, device 0x50, (bus, address) → I2CTransport
try {

    def eeprom = new Eeprom24Aa02UidFull(transport)                              // construct driver, (transport) → Eeprom24Aa02UidFull

    def uid = eeprom.readUid()                                                  // read 32-bit unique serial number, () → byte[]
                                                                                  // reads 4 bytes at 0xFC-0xFF
    def uidHex = uid.collect { String.format('%02X', it & 0xFF) }.join('')
    println("UID bytes: ${uidHex}")

    long uidInt = ((long)(uid[0] & 0xFF) << 24) | ((long)(uid[1] & 0xFF) << 16)
                | ((long)(uid[2] & 0xFF) << 8)  |  (long)(uid[3] & 0xFF)
    println("UID int:   ${uidInt}")

    int mfr = eeprom.readManufacturerCode()                                     // read manufacturer code, () → int
                                                                                  // reads 0xFA; expect 0x29 (Microchip)
    int dev = eeprom.readDeviceCode()                                           // read device code, () → int
                                                                                  // reads 0xFB; expect 0x41
    println(String.format("MFR: 0x%02X  DEV: 0x%02X", mfr, dev))

    int first = eeprom.readByte(0x00)                                           // read a single byte, (address=0x00-0x7F) → int
                                                                                  // random read at user EEPROM address
    println(String.format("First byte: 0x%02X", first))

    eeprom.writeByte(0x10, 0xA5)                                                // write a single byte, (address, value) → void
                                                                                  // byte write + delay until complete (max 5 ms)
    int verify = eeprom.readByte(0x10)                                          // read a single byte, (address=0x00-0x7F) → int
    println(String.format("Wrote 0xA5, read back: 0x%02X", verify))

    byte[] block = eeprom.read(0x20, 8)                                         // sequential read, (address, length) → byte[]
                                                                                  // reads 8 bytes starting at address
    print("Block @ 0x20:")
    for (byte b : block) { print(String.format(" %02X", b & 0xFF)) }
    println()

    byte[] page = [0x01, 0x02, 0x03, 0x04] as byte[]
    eeprom.writePage(0x40, page)                                                // page write, (address, data) → void
                                                                                  // writes up to 8 bytes within one page

    byte[] cross = [0xAA, 0xBB, 0xCC, 0xDD, 0xEE] as byte[]
    eeprom.write(0x44, cross)                                                   // arbitrary-length write, (address, data) → void
                                                                                  // splits at 8-byte page boundaries; waits for each chunk
    println("Multi-page write complete")
} finally {
    transport.close()
}
