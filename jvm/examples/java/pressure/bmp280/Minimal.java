///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.pressure.Bmp280Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x76)) {       // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CTransport
            var sensor = new Bmp280Minimal(transport);                  // construct driver, verifies chip ID and loads calibration, (transport) → Bmp280Minimal

            while (true) {
                double t = sensor.temperature();                        // read temperature, () → double °C
                double p = sensor.pressure();                           // read pressure, () → double hPa
                System.out.printf("temperature=%.2f °C  pressure=%.2f hPa%n", t, p);
                Thread.sleep(1000);
            }
        }
    }
}
