///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.magnetometer.Hmc5883lMinimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x1E)) {            // open I²C bus 1, device 0x1E, (bus, address) → I2CConnection
            var hmc5883l = new Hmc5883lMinimal(connection);                 // construct driver, (connection) → Hmc5883lMinimal

            for (int i = 0; i < 10; i++) {
                Double[] field = hmc5883l.magneticField();  // read magnetic field, () → Double[3] T
                System.out.printf("X=%.6f T  Y=%.6f T  Z=%.6f T%n",
                        field[0] != null ? field[0] : Double.NaN,
                        field[1] != null ? field[1] : Double.NaN,
                        field[2] != null ? field[2] : Double.NaN);
                Thread.sleep(1000);
            }
        }
    }
}