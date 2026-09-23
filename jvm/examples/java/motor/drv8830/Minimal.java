///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.motor.DRV8830Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, DRV8830Minimal.DEFAULT_ADDRESS)) {  // open I²C bus, device address, (bus, address=0x60) → I2CConnection
            var motor = new DRV8830Minimal(connection);                                     // construct driver and confirm presence, (connection) → DRV8830Minimal

            for (int i = 0; i < 5; i++) {
                motor.drive(3.0);                                                             // Drive at regulated voltage, (voltage V, + = forward) → void
                Thread.sleep(2000);
                motor.drive(-3.0);                                                            // Drive at regulated voltage, (voltage V, - = reverse) → void
                Thread.sleep(2000);
            }
            motor.stop();                                                                     // Coast to standby, () → void
        }
    }
}
