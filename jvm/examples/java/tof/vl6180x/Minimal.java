///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL6180XMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, VL6180XMinimal.DEFAULT_ADDRESS, null, null, 2)) {   // open I²C bus, device address, register width 2, (bus, address=0x29, null, null, regBytes=2) → I2CConnection
            var sensor = new VL6180XMinimal(connection);                                          // Create VL6180X driver, (connection) → VL6180XMinimal
            for (int i = 0; i < 50; i++) {
                var mm = sensor.readRange();                                              // Single-shot range, () → int mm
                System.out.println("mm" + " " + mm);
                Thread.sleep(200);
            }
        }
    }
}
