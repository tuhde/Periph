///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ltc4151Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x6A

    I2CConnection(bus, addr).use { connection ->
        val ltc = Ltc4151Full(connection, 0.02)

        checkTrue("voltage() in range 7-80 V", ltc.voltage() in 7.0..80.0)
        checkTrue("current() in range", ltc.current() in 0.0..4.096)
        checkTrue("power() in range", ltc.power() in 0.0..330.0)
        checkTrue("senseVoltage() in range", ltc.senseVoltage() in 0.0..0.08192)
        checkTrue("adinVoltage() in range", ltc.adinVoltage() in 0.0..2.048)
        checkTrue("readAll() vin in range", ltc.readAll().vin >= 7.0)
        checkTrue("readRaw(VIN) in range", ltc.readRaw(Ltc4151Full.VIN) in 1..4095)

        checkTrue("readControl() default 0x0C", ltc.readControl() == 0x0C)
        checkTrue("not in snapshot mode", !ltc.isSnapshotMode())
        checkTrue("snapshot(VIN) in range", ltc.snapshot(Ltc4151Full.VIN) in 7.0..80.0)
        checkTrue("back in continuous scan", !ltc.isSnapshotMode())

        ltc.setStuckBusTimer(false)
        checkTrue("stuck-bus timer off", ltc.readControl() == 0x08)
        ltc.setStuckBusTimer(true)
        checkTrue("stuck-bus timer on", ltc.readControl() == 0x0C)
    }

    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
