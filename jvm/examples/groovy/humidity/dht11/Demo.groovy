///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Full

def dht = new DHT11Full(null)  // Create DHT11 full driver, (transport) -> DHT11Full

def result = dht.readRetry(3)  // Read with retry, (maxRetries=3 int) -> double[] {temperature_C, humidity_RH}
def temp = result[0]
def hum = result[1]

def comfort = hum < 30 ? "dry" : hum <= 60 ? "comfortable" : "humid"

printf("Temperature: %.1f C, Humidity: %.1f %%RH -- %s%n", temp, hum, comfort)
