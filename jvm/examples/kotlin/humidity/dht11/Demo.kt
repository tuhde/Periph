///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport
import it.uhde.periph.chips.humidity.Dht11Full
import it.uhde.periph.chips.humidity.Dht11Minimal

fun comfort(h: Double): String = when {
    h < 30.0 -> "dry"
    h > 60.0 -> "humid"
    else    -> "comfortable"
}

fun main() {
    val lineOffset = System.getenv("DHT11_LINE")?.toIntOrNull() ?: 4
    DHTxxTransport("/dev/gpiochip0", lineOffset).use { transport ->
        val dht = Dht11Full(transport, 3)                 // Create DHT11 driver, (transport, max_retries=3)

        // --- Indoor comfort monitor ---
        // Reads temperature and humidity every 5 seconds and prints a
        // one-line status with a comfort assessment. Demonstrates
        // reliable real-world polling with retry-based error recovery.
        repeat(60) {
            try {
                val (t, h) = dht.readRetry(3)              // Read with retries, (max_retries=3) → (t°C, h%RH)
                println("$t C, $h %RH, ${comfort(h)}")
            } catch (e: Dht11Minimal.Dht11Exception) {
                // --- Handle read failure ---
                // After all retries are exhausted, log a warning and continue.
                // The next loop iteration will try again with a fresh sample.
                println("WARN: DHT11 read failed after retries")
            }
            Thread.sleep(5000)
        }
        println("===DONE: 0 passed, 0 failed===")
    }
}
