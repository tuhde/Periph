///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.memory.MB85RCFull;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x50, null, null, 2)) {   // open I²C bus 1, device 0x50, (bus, address, intPin, enPin, regBytes=2) → I2CConnection

            var fram = new MB85RCFull(connection);                           // construct driver, (connection) → MB85RCFull

            System.out.println("Size: " + fram.size() + " bytes");            // get array size, () → int
                                                                             // always 32768 for the MB85RC256V
            fram.setMaxChunk(16);                                            // set max bytes per bus transaction, (maxChunk=30) → void
            System.out.println("maxChunk: " + fram.getMaxChunk());           // get max bytes per bus transaction, () → int

            fram.writeByte(0x0010, 0xA5);                                    // write a single byte, (address 0–32767, value 0–255) → void
                                                                             // no write cycle, no polling
            System.out.printf("Byte: 0x%02X%n", fram.readByte(0x0010));      // read a single byte, (address 0–32767) → int
                                                                             // random read

            byte[] data = new byte[40];
            for (int i = 0; i < data.length; i++) data[i] = (byte) i;
            fram.write(0x0100, data);                                        // sequential write, (address, data) → void
                                                                             // split into maxChunk-byte transactions
            byte[] back = fram.read(0x0100, 40);                             // sequential read, (address, length) → byte[]
                                                                             // split into maxChunk-byte transactions
            System.out.println("Block length: " + back.length);

            fram.fill(0x0100, 40, 0xFF);                                     // fill a range, (address, length, value=0) → void
                                                                             // writes value over the range in chunks
            System.out.printf("After fill: 0x%02X%n", fram.readByte(0x0100));

            try (var idConnection = new I2CConnection(1, 0x7C)) {            // open I²C bus 1, device 0x7C, (bus, address) → I2CConnection
                int[] id = fram.readDeviceId(idConnection, 0x50);            // read Device ID, (idConnection, address=0x50) → int[3]
                                                                             // reads 3 bytes via reserved slave address 0x7C
                System.out.printf("Device ID: mfr=0x%03X density=0x%X product=0x%02X%n", id[0], id[1], id[2]);
            } catch (Exception e) {
                System.out.println("Device ID not readable on this bus");
            }
        }
    }
}
