///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.memory.Eeprom24Aa02UidMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x50)) {            // open I²C bus 1, device 0x50, (bus, address) → I2CTransport
            var eeprom = new Eeprom24Aa02UidMinimal(transport);              // construct driver, (transport) → Eeprom24Aa02UidMinimal

            for (int i = 0; i < 5; i++) {
                byte[] uid = eeprom.readUid();                                // read 32-bit unique serial number, () → byte[4]
                StringBuilder sb = new StringBuilder();
                for (byte b : uid) { sb.append(String.format("%02X", b & 0xFF)); }
                System.out.println("UID: " + sb);
                Thread.sleep(2000);
            }
        }
    }
}
