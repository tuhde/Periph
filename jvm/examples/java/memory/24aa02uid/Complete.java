///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.memory.Eeprom24Aa02UidFull;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x50)) {            // open I²C bus 1, device 0x50, (bus, address) → I2CTransport

            var eeprom = new Eeprom24Aa02UidFull(transport);                  // construct driver, (transport) → Eeprom24Aa02UidFull

            byte[] uid = eeprom.readUid();                                     // read 32-bit unique serial number, () → byte[4]
                                                                              // reads 4 bytes at 0xFC-0xFF
            StringBuilder uidHex = new StringBuilder();
            for (byte b : uid) { uidHex.append(String.format("%02X", b & 0xFF)); }
            System.out.println("UID bytes: " + uidHex);

            long uidInt = ((long)(uid[0] & 0xFF) << 24) | ((long)(uid[1] & 0xFF) << 16)
                        | ((long)(uid[2] & 0xFF) << 8)  |  (long)(uid[3] & 0xFF);
            System.out.println("UID int:   " + uidInt);

            int mfr = eeprom.readManufacturerCode();                           // read manufacturer code, () → int
                                                                              // reads 0xFA; expect 0x29 (Microchip)
            int dev = eeprom.readDeviceCode();                                 // read device code, () → int
                                                                              // reads 0xFB; expect 0x41
            System.out.printf("MFR: 0x%02X  DEV: 0x%02X%n", mfr, dev);

            int first = eeprom.readByte(0x00);                                 // read a single byte, (address=0x00-0x7F) → int
                                                                              // random read at user EEPROM address
            System.out.printf("First byte: 0x%02X%n", first);

            eeprom.writeByte(0x10, 0xA5);                                      // write a single byte, (address, value) → void
                                                                              // byte write + delay until complete (max 5 ms)
            int verify = eeprom.readByte(0x10);                               // read a single byte, (address=0x00-0x7F) → int
            System.out.printf("Wrote 0xA5, read back: 0x%02X%n", verify);

            byte[] block = eeprom.read(0x20, 8);                               // sequential read, (address, length) → byte[]
                                                                              // reads 8 bytes starting at address
            StringBuilder blk = new StringBuilder("Block @ 0x20: ");
            for (byte b : block) { blk.append(String.format("%02X ", b & 0xFF)); }
            System.out.println(blk);

            byte[] page = new byte[]{ 0x01, 0x02, 0x03, 0x04 };
            eeprom.writePage(0x40, page);                                      // page write, (address, data) → void
                                                                              // writes up to 8 bytes within one page

            byte[] cross = new byte[]{ (byte)0xAA, (byte)0xBB, (byte)0xCC, (byte)0xDD, (byte)0xEE };
            eeprom.write(0x44, cross);                                         // arbitrary-length write, (address, data) → void
                                                                              // splits at 8-byte page boundaries; waits for each chunk
            System.out.println("Multi-page write complete");
        }
    }
}
