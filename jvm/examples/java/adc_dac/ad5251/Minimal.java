///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.AD5251Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, AD5251Minimal.DEFAULT_ADDRESS)) {  // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
            var pot = new AD5251Minimal(connection);                                       // construct driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω) → AD5251Minimal
            int[] codes = {0, 16, 32, 48, 63};
            for (int code : codes) {
                pot.setWiper(0, code);                                                              // Set wiper position, (channel 0–1, code 0–63) → void
                System.out.println(code + " " + pot.getWiper(0));                                   // Read wiper position, (channel 0–1) → int 0–63
                System.out.println(pot.getResistance(0));                                           // Read W–B resistance, (channel 0–1) → double Ω
                Thread.sleep(500);
            }
            pot.setResistance(1, 20000.0);                                                           // Set W–B resistance, (channel 0–1, ohms Ω) → void
        }
    }
}
