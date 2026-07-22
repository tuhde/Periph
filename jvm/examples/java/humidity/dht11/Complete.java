///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport;
import it.uhde.periph.chips.humidity.Dht11Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int lineOffset = Integer.parseInt(System.getenv().getOrDefault("DHT11_LINE", "4"));
        try (var transport = new DHTxxTransport("/dev/gpiochip0", lineOffset)) {
            var dht = new Dht11Full(transport, 3);                 // Create DHT11 driver, (transport, max_retries=3)
            double t = dht.readTemperature();                     // Read temperature, () → double °C
                                                                 // returns a fresh conversion each call
            double h = dht.readHumidity();                        // Read humidity, () → double %RH
                                                                 // returns a fresh conversion each call
            double[] r = dht.readRetry(5);                        // Read with retries, (max_retries=5) → [t°C, h%RH]
                                                                 // retries up to 5 times on checksum error
            byte[] raw = dht.readRaw();                           // Read raw frame, () → byte[5]
                                                                 // returns the validated 5-byte frame
            System.out.println("t=" + t + " h=" + h + " retry_t=" + r[0] + " raw[0]=0x" + String.format("%02X", raw[0] & 0xFF));
            System.out.println("===DONE: 0 passed, 0 failed===");
        }
    }
}
