///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.MCP3422Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, MCP3422Minimal.DEFAULT_ADDRESS)) {             // open I²C bus, device address, (bus, address=0x68) → I2CConnection
            var adc = new MCP3422Minimal(connection);                                               // construct driver, (connection) → MCP3422Minimal
            while (true) {
                for (int ch = 0; ch <= 1; ch++) {
                    int raw = adc.readRaw(ch);                                                      // read a signed code, (channel 0–1) → int
                    double volts = adc.readVoltage(ch);                                             // read the differential voltage, (channel 0–1) → double V
                    System.out.println("CH" + (ch + 1) + ": " + raw + " -> " + volts + " V");
                }
                System.out.println(adc.readAll());                                                  // read both channels, () → double[] V
                Thread.sleep(1000);
            }
        }
    }
}
