///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Full

def passed = 0
def failed = 0

def dht = new DHT11Full(null)

try {
    def raw = dht.readRaw()
    def sum = ((raw[0] & 0xFF) + (raw[1] & 0xFF) + (raw[2] & 0xFF) + (raw[3] & 0xFF)) & 0xFF
    if (sum == (raw[4] & 0xFF)) {
        println("PASS checksum")
        passed++
    } else {
        println("FAIL checksum")
        failed++
    }
} catch (Exception e) {
    println("FAIL checksum: ${e.message}")
    failed++
}

try {
    def result = dht.read()
    if (result[0] > -40 && result[0] < 80) {
        println("PASS temperature_range")
        passed++
    } else {
        println("FAIL temperature_range")
        failed++
    }
    if (result[1] >= 0 && result[1] <= 100) {
        println("PASS humidity_range")
        passed++
    } else {
        println("FAIL humidity_range")
        failed++
    }
} catch (Exception e) {
    println("FAIL read: ${e.message}")
    failed++
}

printf("===DONE: %d passed, %d failed===%n", passed, failed)
System.exit(failed > 0 ? 1 : 0)
