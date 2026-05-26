///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Full

fun main() {
    val dht = DHT11Full(null)  // Create DHT11 full driver, (transport) -> DHT11Full

    val temp = dht.readTemperature()  // Read temperature, () -> Double C
    val hum = dht.readHumidity()  // Read humidity, () -> Double %RH
    println("Temperature: %.1f C, Humidity: %.1f %%RH".format(temp, hum))

    val raw = dht.readRaw()  // Return raw 5-byte frame, () -> ByteArray
    System.out.printf("Raw: %02X %02X %02X %02X %02X%n",
        raw[0].toInt() and 0xFF, raw[1].toInt() and 0xFF, raw[2].toInt() and 0xFF,
        raw[3].toInt() and 0xFF, raw[4].toInt() and 0xFF)
}
