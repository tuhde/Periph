///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.DHTxxConnection
import it.uhde.periph.chips.humidity.Dht11Minimal

fun main() {
    val lineOffset = System.getenv("DHT11_LINE")?.toIntOrNull() ?: 4
    DHTxxConnection("/dev/gpiochip0", lineOffset).use { connection ->
        val dht = Dht11Minimal(connection)                // Create DHT11 driver, (connection)
        repeat(5) {
            val (t, h) = dht.read()                       // Read temperature & humidity, () → (t°C, h%RH)
            println("$t C, $h %RH")
            Thread.sleep(2000)
        }
        println("===DONE: 0 passed, 0 failed===")
    }
}
