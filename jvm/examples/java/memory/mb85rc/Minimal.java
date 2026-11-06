///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.memory.MB85RCMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x50, null, null, 2)) {   // open I²C bus 1, device 0x50, (bus, address, intPin, enPin, regBytes=2) → I2CConnection
            var fram = new MB85RCMinimal(connection);                        // construct driver, (connection) → MB85RCMinimal

            for (int i = 0; i < 5; i++) {
                fram.writeByte(0x0000, i);                                   // write a single byte, (address 0–32767, value 0–255) → void
                int value = fram.readByte(0x0000);                           // read a single byte, (address 0–32767) → int
                System.out.println("Stored: " + value);
                Thread.sleep(1000);
            }
        }
    }
}
