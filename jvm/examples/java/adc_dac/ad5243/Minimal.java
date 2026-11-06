///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.AD5243Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, AD5243Minimal.DEFAULT_ADDRESS)) {  // open I²C bus, device address, (bus, address=0x2F) → I2CConnection
            var pot = new AD5243Minimal(connection);                                       // construct driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω) → AD5243Minimal
            int[] codes = {0, 64, 128, 192, 255};
            for (int code : codes) {
                pot.setWiper(0, code);                                                              // Set wiper position, (channel 0–1, code 0–255) → void
                System.out.println(code + " " + pot.getWiper(0));                                   // Read wiper position, (channel 0–1) → int 0–255
                System.out.println(pot.getResistance(0));                                           // Read W–B resistance, (channel 0–1) → double Ω
                Thread.sleep(500);
            }
            pot.setResistance(1, 2200.0);                                                           // Set W–B resistance, (channel 0–1, ohms Ω) → void
        }
    }
}
