///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Full

fun main() {
    val dht = DHT11Full(null)  // Create DHT11 full driver, (transport) -> DHT11Full

    val result = dht.readRetry(3)  // Read with retry, (maxRetries=3 int) -> DoubleArray {temperature_C, humidity_RH}
    val temp = result[0]
    val hum = result[1]

    val comfort = when {
        hum < 30 -> "dry"
        hum <= 60 -> "comfortable"
        else -> "humid"
    }

    println("Temperature: %.1f C, Humidity: %.1f %%RH -- %s".format(temp, hum, comfort))
}
