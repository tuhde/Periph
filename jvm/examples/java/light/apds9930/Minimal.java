///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Apds9930Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x39)) {            // Open I2C connection, (bus=1, address=0x39) → I2CConnection
            var apds = new Apds9930Minimal(connection);                 // Create APDS-9930 driver, (connection) → Apds9930Minimal
                                                                       // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20
            Thread.sleep(110);
            for (int i = 0; i < 10; i++) {
                float lx = apds.lux();                                  // Read ambient illuminance, () → float lx
                                                                       // IR-compensated lux via Ch0/Ch1 difference
                int p = apds.proximity();                                // Read proximity count, () → int count
                                                                       // 16-bit ADC value; higher = closer object
                System.out.printf("lux=%.1f lx  proximity=%d%n", lx, p);
                Thread.sleep(1000);
            }
        }
    }
}