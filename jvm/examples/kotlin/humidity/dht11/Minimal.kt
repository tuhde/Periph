///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport
import it.uhde.periph.chips.humidity.Dht11Minimal

fun main() {
    val lineOffset = System.getenv("DHT11_LINE")?.toIntOrNull() ?: 4
    DHTxxTransport("/dev/gpiochip0", lineOffset).use { transport ->
        val dht = Dht11Minimal(transport)                // Create DHT11 driver, (transport)
        repeat(5) {
            val (t, h) = dht.read()                       // Read temperature & humidity, () → (t°C, h%RH)
            println("$t C, $h %RH")
            Thread.sleep(2000)
        }
        println("===DONE: 0 passed, 0 failed===")
    }
}
