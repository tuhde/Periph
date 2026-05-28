///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.environmental.Aht21Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x38)) {            // open I²C bus 1, device 0x38, (bus, address) → I2CTransport
            var aht = new Aht21Minimal(transport);                        // construct driver, (transport) → Aht21Minimal

            for (int i = 0; i < 10; i++) {
                double[] r = aht.read();    // trigger measurement, () → double[] {temperature_c, humidity_pct}
                System.out.printf("T=%.2f °C  H=%.2f %%RH%n", r[0], r[1]);
                Thread.sleep(1000);
            }
        }
    }
}
