///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport
import it.uhde.periph.chips.humidity.Dht11Minimal

def lineOffset = (System.getenv('DHT11_LINE') ?: '4') as int
def transport = new DHTxxTransport('/dev/gpiochip0', lineOffset)
try {
    def dht = new Dht11Minimal(transport)                            // Create DHT11 driver, (transport)
    5.times {
        def r = dht.read()                                           // Read temperature & humidity, () → [t°C, h%RH]
        println("${r[0]} C, ${r[1]} %RH")
        Thread.sleep(2000)
    }
    println('===DONE: 0 passed, 0 failed===')
} finally {
    transport.close()
}
