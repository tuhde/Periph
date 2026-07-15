///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport
import it.uhde.periph.chips.humidity.Dht11Full

def lineOffset = (System.getenv('DHT11_LINE') ?: '4') as int
def transport = new DHTxxTransport('/dev/gpiochip0', lineOffset)
try {
    def dht = new Dht11Full(transport, 3)                            // Create DHT11 driver, (transport, max_retries=3)
    def t = dht.readTemperature()                                    // Read temperature, () → double °C
                                                                     // returns a fresh conversion each call
    def h = dht.readHumidity()                                       // Read humidity, () → double %RH
                                                                     // returns a fresh conversion each call
    def r = dht.readRetry(5)                                         // Read with retries, (max_retries=5) → [t°C, h%RH]
                                                                     // retries up to 5 times on checksum error
    def raw = dht.readRaw()                                          // Read raw frame, () → byte[5]
                                                                     // returns the validated 5-byte frame
    printf("t=%s h=%s retry_t=%s raw[0]=0x%02X%n", t, h, r[0], raw[0] & 0xFF)
    println('===DONE: 0 passed, 0 failed===')
} finally {
    transport.close()
}
