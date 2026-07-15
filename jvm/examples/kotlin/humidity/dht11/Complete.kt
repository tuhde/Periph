///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport
import it.uhde.periph.chips.humidity.Dht11Full

fun main() {
    val lineOffset = System.getenv("DHT11_LINE")?.toIntOrNull() ?: 4
    DHTxxTransport("/dev/gpiochip0", lineOffset).use { transport ->
        val dht = Dht11Full(transport, 3)                 // Create DHT11 driver, (transport, max_retries=3)
        val t = dht.readTemperature()                     // Read temperature, () → Double °C
                                                          // returns a fresh conversion each call
        val h = dht.readHumidity()                        // Read humidity, () → Double %RH
                                                          // returns a fresh conversion each call
        val r = dht.readRetry(5)                          // Read with retries, (max_retries=5) → (t°C, h%RH)
                                                          // retries up to 5 times on checksum error
        val raw = dht.readRaw()                           // Read raw frame, () → ByteArray
                                                          // returns the validated 5-byte frame
        println("t=$t h=$h retry_t=${r.first} raw[0]=0x${"%02X".format(raw[0].toInt() and 0xFF)}")
        println("===DONE: 0 passed, 0 failed===")
    }
}
