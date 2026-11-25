///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.adc_dac.Hy3131Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0, 0, 1_000_000)) {                              // Open SPI bus 0, CS 0, Mode 0, 1 MHz, (bus, device, mode=0, maxSpeedHz=1_000_000) → SPIConnection
            var adc = new Hy3131Minimal(connection, 1.2f);                                          // Create HY3131 driver, (connection, vref=1.2 V) → Hy3131Minimal
            while (true) {
                float v = adc.readVoltage();                                                        // Read AD1 differential voltage, () → float V
                System.out.printf("%.5f%n", v);
                Thread.sleep(1000);
            }
        }
    }
}
