///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.environmental.Bme680Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x76)) {       // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CTransport
            var sensor = new Bme680Minimal(transport);                  // construct driver, verifies chip ID and loads calibration, (transport) → Bme680Minimal

            while (true) {
                double t = sensor.temperature();                        // read temperature, () → double °C
                double p = sensor.pressure();                           // read pressure, () → double hPa
                double h = sensor.humidity();                           // read humidity, () → double %RH
                double g = sensor.gasResistance();                      // read gas resistance, () → double Ω
                System.out.printf("temperature=%.2f °C  pressure=%.2f hPa  humidity=%.1f %%RH  gas=%.0f Ω%n", t, p, h, g);
                Thread.sleep(1000);
            }
        }
    }
}
