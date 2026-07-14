///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.memory.Eeprom24Aa02UidFull;

/**
 * Device-tracking demo: on startup, read the 32-bit unique serial number
 * and print it as an 8-character hex string. Maintain a 4-byte boot
 * counter in user EEPROM at 0x00-0x03. Then loop reading only the UID
 * every 2 seconds to show that the UID never changes while the counter
 * does. The two distinct areas of the chip (immutable identification
 * above 0x80, rewritable storage below 0x80) are exercised independently.
 */
public class Demo {

    private static final int    SAMPLES     = 5;
    private static final long   INTERVAL_MS = 2000;

    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x50)) {            // open I²C bus 1, device 0x50, (bus, address) → I2CTransport
            var eeprom = new Eeprom24Aa02UidFull(transport);                  // construct driver, (transport) → Eeprom24Aa02UidFull

            // --- Read the chip's factory-programmed 32-bit serial number ---
            // The UID at 0xFC-0xFF never changes and identifies the device
            // across the entire 256-byte address space.
            byte[] uid = eeprom.readUid();                                     // read 32-bit unique serial number, () → byte[4]
                                                                              // reads 4 bytes at 0xFC-0xFF
            StringBuilder sb = new StringBuilder();
            for (byte b : uid) { sb.append(String.format("%02X", b & 0xFF)); }
            System.out.println("Device UID: 0x" + sb);
            long uidInt = ((long)(uid[0] & 0xFF) << 24) | ((long)(uid[1] & 0xFF) << 16)
                        | ((long)(uid[2] & 0xFF) << 8)  |  (long)(uid[3] & 0xFF);
            System.out.println("Device UID int: " + uidInt);

            // --- Maintain a 4-byte boot counter in user EEPROM at 0x00-0x03 ---
            // Read the existing value (or zero on a fresh chip), increment,
            // write back as 4 big-endian bytes. The user EEPROM is rewritable;
            // the UID region above 0x80 is not, so the two stay independent.
            byte[] existing = eeprom.read(0x00, 4);                            // sequential read, (address, length) → byte[]
                                                                              // reads 4 bytes from user EEPROM
            long counter = ((long)(existing[0] & 0xFF) << 24) | ((long)(existing[1] & 0xFF) << 16)
                         | ((long)(existing[2] & 0xFF) << 8)  |  (long)(existing[3] & 0xFF);
            counter += 1;
            byte[] updated = new byte[]{
                (byte)((counter >> 24) & 0xFF), (byte)((counter >> 16) & 0xFF),
                (byte)((counter >> 8)  & 0xFF), (byte)(counter & 0xFF)
            };
            eeprom.write(0x00, updated);                                       // arbitrary-length write, (address, data) → void
                                                                              // writes 4 bytes; waits for each chunk
            System.out.println("Boot count: " + counter);

            for (int n = 0; n < SAMPLES; n++) {
                // --- Loop reading the UID only, showing it never changes ---
                // The two distinct areas of the chip (immutable identification
                // above 0x80, rewritable storage below 0x80) are exercised
                // independently.
                byte[] u = eeprom.readUid();                                   // read 32-bit unique serial number, () → byte[4]
                StringBuilder s2 = new StringBuilder();
                for (byte b : u) { s2.append(String.format("%02X", b & 0xFF)); }
                System.out.println("[" + n + "] UID: 0x" + s2 + "  (counter at user EEPROM 0x00-0x03)");
                Thread.sleep(INTERVAL_MS);
            }
        }
    }
}
