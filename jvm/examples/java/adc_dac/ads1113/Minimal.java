///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1113Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, ADS1113Minimal.DEFAULT_ADDRESS)) {    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
            var adc = new ADS1113Minimal(connection);                                       // construct driver, (connection) → ADS1113Minimal

            for (int i = 0; i < 10; i++) {
                var v = adc.readVoltage();                                                  // Read voltage, () → double V
                System.out.printf("%.5f V%n", v);
                Thread.sleep(1000);
            }
        }
    }
}
