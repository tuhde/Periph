///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Full

def dht = new DHT11Full(null)  // Create DHT11 full driver, (transport) -> DHT11Full

def temp = dht.readTemperature()  // Read temperature, () -> double C
def hum = dht.readHumidity()  // Read humidity, () -> double %RH
printf("Temperature: %.1f C, Humidity: %.1f %%RH%n", temp, hum)

def raw = dht.readRaw()  // Return raw 5-byte frame, () -> byte[]
printf("Raw: %02X %02X %02X %02X %02X%n",
    raw[0] & 0xFF, raw[1] & 0xFF, raw[2] & 0xFF, raw[3] & 0xFF, raw[4] & 0xFF)
