///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.tof.VL53L0XMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, VL53L0XMinimal.DEFAULT_ADDRESS)) {   // open I²C bus, device address, (bus, address=0x29) → I2CConnection
            var sensor = new VL53L0XMinimal(connection);                                  // Create VL53L0X driver, (connection) → VL53L0XMinimal

            for (int i = 0; i < 50; i++) {
                int d = sensor.distance();                                                // Measure distance, () → int mm
                if (sensor.rangeValid()) {                                                // Check last measurement, () → boolean
                    System.out.printf("%d mm%n", d);
                } else {
                    System.out.println("out of range");
                }
                Thread.sleep(100);
            }
        }
    }
}
