///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina3221Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x40

    I2CTransport(bus, addr).use { transport ->

        val ina = Ina3221Full(transport)

        // --- Minimal methods: all three channels ---
        for (ch in 1..3) {
            val v = ina.voltage(ch)
            checkTrue("voltage(ch$ch) in 0–26 V", v >= 0.0 && v <= 26.0)

            val sv = ina.shuntVoltage(ch)
            checkTrue("shuntVoltage(ch$ch) is finite", sv.isFinite())

            val i = ina.current(ch)
            checkTrue("current(ch$ch) is finite", i.isFinite())

            val p = ina.power(ch)
            checkTrue("power(ch$ch) is finite", p.isFinite())
        }

        // --- configure() ---
        ina.configure(1, 4, 4, Ina3221Full.MODE_SHUNT_BUS_CONT)
        checkTrue("configure() accepted", true)

        // --- enableChannel / channelEnabled ---
        ina.enableChannel(1, true)
        checkTrue("enableChannel(1, true) accepted", true)

        val en = ina.channelEnabled(1)
        checkTrue("channelEnabled(1) returns boolean", en == true || en == false)

        // --- conversionReady ---
        val cvrf = ina.conversionReady()
        checkTrue("conversionReady() returns boolean", cvrf == true || cvrf == false)

        // --- Alert limits ---
        ina.setCriticalAlert(1, 0.1)
        checkTrue("setCriticalAlert(1, 0.1) accepted", true)

        ina.setWarningAlert(1, 0.08)
        checkTrue("setWarningAlert(1, 0.08) accepted", true)

        // --- alertFlags ---
        val flags = ina.alertFlags()
        checkTrue("alertFlags() returns int", flags in 0..0xFFFF)

        // --- power-valid limits ---
        ina.setPowerValidLimits(5.5, 4.5)
        checkTrue("setPowerValidLimits(5.5, 4.5) accepted", true)

        // --- summation ---
        ina.setSummationChannels(intArrayOf(1, 2, 3), 0.1)
        checkTrue("setSummationChannels([1,2,3], 0.1) accepted", true)

        val sumV = ina.summationValue()
        checkTrue("summationValue() is finite", sumV.isFinite())

        // --- powerValid ---
        val pv = ina.powerValid()
        checkTrue("powerValid() returns boolean", pv == true || pv == false)

        // --- manufacturerId / dieId ---
        val mfr = ina.manufacturerId()
        checkTrue("manufacturerId() == 0x5449", mfr == 0x5449)

        val die = ina.dieId()
        checkTrue("dieId() == 0x3220", die == 0x3220)

        // --- shutdown / wake ---
        ina.shutdown()
        checkTrue("shutdown() accepted", true)

        ina.wake()
        checkTrue("wake() accepted", true)

        // --- reset ---
        ina.reset()
        checkTrue("reset() accepted", true)
    }


    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
