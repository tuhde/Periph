///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.DS3502Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, DS3502Minimal.DEFAULT_ADDRESS)) {             // open I²C bus, device address, (bus, address=0x28) → I2CConnection
            var pot = new DS3502Minimal(connection, 15.0, 0.0);                                    // construct driver, (connection, vHigh=1.0 V, vLow=0.0 V) → DS3502Minimal
            int[] codes = {0, 32, 64, 96, 127};
            for (int code : codes) {
                pot.setWiper(code);                                                                // Set wiper position, (code 0–127) → void
                System.out.println("" + code + " " + pot.getWiper());                              // Read wiper position, () → int 0–127
                System.out.println(pot.getVoltage());                                              // Read unloaded RW voltage, () → double V
                Thread.sleep(500);
            }
            pot.setVoltage(6.0);                                                                   // Set RW output voltage, (volts V) → void
        }
    }
}
