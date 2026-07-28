///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.DHTxxConnection
import it.uhde.periph.chips.humidity.Dht11Minimal

def lineOffset = (System.getenv('DHT11_LINE') ?: '4') as int
def connection = new DHTxxConnection('/dev/gpiochip0', lineOffset)
try {
    def dht = new Dht11Minimal(connection)                            // Create DHT11 driver, (connection)
    5.times {
        def r = dht.read()                                           // Read temperature & humidity, () → [t°C, h%RH]
        println("${r[0]} C, ${r[1]} %RH")
        Thread.sleep(2000)
    }
    println('===DONE: 0 passed, 0 failed===')
} finally {
    connection.close()
}
