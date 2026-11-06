///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-java:1.3.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.AD5248Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x2C)) {          // open I²C bus 1, device 0x2C, (bus, address) → I2CConnection
            var pot = new AD5248Minimal(connection, 10000.0, 160.0); // construct driver, (connection, rAb=10000 Ω, rWiper=160 Ω) → AD5248Minimal

            pot.setWiper(0, 64);                                 // set channel 0 wiper, (channel 0-1, code 0-255) → void
            pot.setResistance(1, 2200.0);                        // set channel 1 W-B resistance, (channel 0-1, ohms) → void

            for (int i = 0; i < 10; i++) {
                int code = pot.getWiper(0);                      // read channel 0 wiper, (channel 0-1) → int 0–255
                double ohms = pot.getResistance(0);              // read channel 0 W-B resistance, (channel 0-1) → double Ω
                System.out.printf("ch0 code=%d  R_WB=%.0f ohm%n", code, ohms);
                Thread.sleep(1000);
            }
        }
    }
}
