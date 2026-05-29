///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Aht21Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x38

    I2CTransport(bus, addr).use { transport ->

        val aht = Aht21Full(transport)

        checkTrue("isCalibrated", aht.isCalibrated())
        checkTrue("not busy at idle", !aht.isBusy())

        val (t, h) = aht.read()
        checkTrue("temperature range", t in -40.0..120.0)
        checkTrue("humidity range", h in 0.0..100.0)

        val tr = aht.readTemperature()
        checkTrue("readTemperature range", tr in -40.0..120.0)

        val hr = aht.readHumidity()
        checkTrue("readHumidity range", hr in 0.0..100.0)

        val (tc, hc, crcOk) = aht.readWithCrc()
        checkTrue("crc_ok", crcOk)
        checkTrue("crc temperature range", tc in -40.0..120.0)
        checkTrue("crc humidity range", hc in 0.0..100.0)

        aht.softReset()
        Thread.sleep(50)
        checkTrue("calibrated after reset", aht.isCalibrated())

        val (t2, h2) = aht.read()
        checkTrue("read after reset: temperature range", t2 in -40.0..120.0)
        checkTrue("read after reset: humidity range", h2 in 0.0..100.0)
    }

    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
