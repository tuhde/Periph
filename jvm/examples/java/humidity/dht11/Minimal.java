///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport;
import it.uhde.periph.chips.humidity.Dht11Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int lineOffset = Integer.parseInt(System.getenv().getOrDefault("DHT11_LINE", "4"));
        try (var transport = new DHTxxTransport("/dev/gpiochip0", lineOffset)) {
            var dht = new Dht11Minimal(transport);                // Create DHT11 driver, (transport)
            for (int i = 0; i < 5; i++) {
                double[] r = dht.read();                          // Read temperature & humidity, () → [t°C, h%RH]
                System.out.println(r[0] + " C, " + r[1] + " %RH");
                Thread.sleep(2000);
            }
            System.out.println("===DONE: 0 passed, 0 failed===");
        }
    }
}
