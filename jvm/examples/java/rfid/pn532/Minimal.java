///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rfid.Pn532Minimal;

import java.util.HexFormat;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x24").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var reader = new Pn532Minimal(connection);                                              // Create PN532 driver, (connection) → Pn532Minimal
            reader.init();                                                                          // Wake, check firmware, SAM normal mode, finite retries, () → void

            while (true) {
                byte[] uid = reader.readUid();                                                      // Detect a type A card, (timeout=1.0 s) → byte[] | null
                System.out.println("UID " + (uid == null ? "none" : HexFormat.of().withUpperCase().formatHex(uid)));
                Thread.sleep(1000);
            }
        }
    }
}
