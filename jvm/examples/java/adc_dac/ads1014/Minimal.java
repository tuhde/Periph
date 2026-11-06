///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.ADS1014Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, ADS1014Minimal.DEFAULT_ADDRESS)) {   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
            var adc = new ADS1014Minimal(connection);                                   // construct driver, (connection) → ADS1014Minimal

            for (int i = 0; i < 10; i++) {
                var raw = adc.readRaw();                                                // Read raw code, () → int (−2048…2047)
                var v = adc.readVoltage();                                              // Read voltage, () → double V
                System.out.printf("%d -> %.3f V%n", raw, v);
                Thread.sleep(1000);
            }
        }
    }
}
