///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.CS43L22Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, CS43L22Minimal.DEFAULT_ADDRESS)) {              // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
            var dac = new CS43L22Minimal(connection);                                                // Create CS43L22 driver, (connection, resPin=null) → CS43L22Minimal
            dac.init();                                                                              // Reset, verify ID, load defaults, () → void
            dac.start();                                                                             // Power up the DAC once MCLK is running, () → void
            dac.setVolume(-20.0);                                                                    // Set master volume, (db -102–12 dB) → void
            dac.mute(false);                                                                         // Unmute the headphone outputs, (on) → void
            for (int i = 0; i < 5; i++) {
                System.out.println(dac.getVolume());                                                 // Read back master volume, () → double dB
                Thread.sleep(1000);
            }
            dac.stop();                                                                              // Power down the DAC, () → void
        }
    }
}
