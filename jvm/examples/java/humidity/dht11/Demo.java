///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        var dht = new DHT11Full(null);  // Create DHT11 full driver, (transport) -> DHT11Full

        double[] result = dht.readRetry(3);  // Read with retry, (maxRetries=3 int) -> double[] {temperature_C, humidity_RH}
        double temp = result[0];
        double hum = result[1];

        String comfort;
        if (hum < 30) {
            comfort = "dry";
        } else if (hum <= 60) {
            comfort = "comfortable";
        } else {
            comfort = "humid";
        }

        System.out.printf("Temperature: %.1f C, Humidity: %.1f %%RH -- %s%n", temp, hum, comfort);
    }
}
