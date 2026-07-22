///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport
import it.uhde.periph.chips.humidity.Dht11Full
import it.uhde.periph.chips.humidity.Dht11Minimal

def comfort(h) {
    if (h < 30.0) return 'dry'
    if (h > 60.0) return 'humid'
    return 'comfortable'
}

def lineOffset = (System.getenv('DHT11_LINE') ?: '4') as int
def transport = new DHTxxTransport('/dev/gpiochip0', lineOffset)
try {
    def dht = new Dht11Full(transport, 3)                            // Create DHT11 driver, (transport, max_retries=3)

    // --- Indoor comfort monitor ---
    // Reads temperature and humidity every 5 seconds and prints a
    // one-line status with a comfort assessment. Demonstrates
    // reliable real-world polling with retry-based error recovery.
    60.times {
        try {
            def r = dht.readRetry(3)                                 // Read with retries, (max_retries=3) → [t°C, h%RH]
            println("${r[0]} C, ${r[1]} %RH, ${comfort(r[1])}")
        } catch (Dht11Minimal.Dht11Exception e) {
            // --- Handle read failure ---
            // After all retries are exhausted, log a warning and continue.
            // The next loop iteration will try again with a fresh sample.
            println('WARN: DHT11 read failed after retries')
        }
        Thread.sleep(5000)
    }
    println('===DONE: 0 passed, 0 failed===')
} finally {
    transport.close()
}
