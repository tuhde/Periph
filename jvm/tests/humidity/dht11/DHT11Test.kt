///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Full

fun main() {
    var passed = 0
    var failed = 0

    val dht = DHT11Full(null)

    try {
        val raw = dht.readRaw()
        val sum = (raw[0].toInt() + raw[1].toInt() + raw[2].toInt() + raw[3].toInt()) and 0xFF
        if (sum == (raw[4].toInt() and 0xFF)) {
            println("PASS checksum")
            passed++
        } else {
            println("FAIL checksum")
            failed++
        }
    } catch (e: Exception) {
        println("FAIL checksum: ${e.message}")
        failed++
    }

    try {
        val result = dht.read()
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
    } catch (e: Exception) {
        println("FAIL read: ${e.message}")
        failed++
    }

    println("===DONE: %d passed, %d failed===.%n".format(passed, failed))
    System.exit(if (failed > 0) 1 else 0)
}
