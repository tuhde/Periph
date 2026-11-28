///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.rfid.St25dvMinimal;

import java.nio.charset.StandardCharsets;

public class Minimal {

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var user = new I2CConnection(bus, 0x53, null, null, 2);                  // Create user-area connection, (bus, addr=0x53, int=null, en=null, regBytes=2)
             var system = new I2CConnection(bus, 0x57, null, null, 2)) {              // Create system-area connection, (bus, addr=0x57, int=null, en=null, regBytes=2)
            var tag = new St25dvMinimal(user, system);                                  // Create ST25DV driver, (user, system, gpoActiveHigh=false) → void

            tag.write(0x0100, "Hello NFC".getBytes(StandardCharsets.US_ASCII));         // Write user memory, (address, data) → void
            while (true) {
                byte[] b = tag.read(0x0100, 9);                                         // Read user memory, (address, length) → byte[]
                System.out.println("Memory: " + new String(b, StandardCharsets.US_ASCII));
                Thread.sleep(1000);
            }
        }
    }
}
