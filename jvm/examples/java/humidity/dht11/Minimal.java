///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        var dht = new DHT11Minimal(null);  // Create DHT11 minimal driver, (transport) -> DHT11Minimal

        double[] result = dht.read();  // Read temperature and humidity, () -> double[] {temperature_C, humidity_RH}
        System.out.printf("Temperature: %.1f C, Humidity: %.1f %%RH%n", result[0], result[1]);
    }
}
