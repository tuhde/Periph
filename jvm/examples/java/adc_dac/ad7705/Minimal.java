///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.adc_dac.Ad7705Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0, 3, 5_000_000)) {   // Open SPI bus 0, CS 0, Mode 3, 5 MHz, (bus, device, mode=3, maxSpeedHz=5_000_000) → SPIConnection
            var adc = new Ad7705Minimal(connection, 2.5f, Ad7705Minimal.MCLK_2_4576MHZ);    // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → Ad7705Minimal

            while (true) {
                float v = adc.readVoltage();                                       // Read Channel 1 voltage, () → float V
                System.out.printf("%.4f%n", v);
                Thread.sleep(1000);
            }
        }
    }
}
