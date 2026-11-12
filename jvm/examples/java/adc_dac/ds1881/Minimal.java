///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.DS1881Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, DS1881Minimal.DEFAULT_ADDRESS)) {                    // open I²C bus, device address, (bus, address=0x28) → I2CConnection
            var amp = new DS1881Minimal(connection);                                                // construct driver, (connection) → DS1881Minimal
            amp.init();                                                                             // Apply defaults, () → void
            for (double db : new double[] {0.0, 10.0, 20.0, 40.0}) {
                amp.setAttenuation(0, db);                                                          // Set attenuation, (channel 0–1, db dB) → void
                amp.setAttenuation(1, db);                                                          // Set attenuation, (channel 0–1, db dB) → void
                System.out.println("" + db + " " + amp.getAttenuation(0));                          // Read attenuation, (channel 0–1) → double dB
                Thread.sleep(500);
            }
            amp.mute(0);                                                                            // Mute channel, (channel 0–1) → void
        }
    }
}
