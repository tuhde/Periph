///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina226Full

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

        val ina = Ina226Full(transport, 0.1, 2.0)

        // --- Basic measurements ---
        val v = ina.voltage()
        checkTrue("voltage() in range 0–36 V", v in 0.0..36.0)

        val vs = ina.shuntVoltage()
        checkTrue("shuntVoltage() is finite", vs.isFinite())

        val c = ina.current()
        checkTrue("current() is finite", c.isFinite())

        val p = ina.power()
        checkTrue("power() is finite", p.isFinite())

        // --- Configuration ---
        ina.configure(Ina226Full.AVG_128, Ina226Full.CT_1100US,
                      Ina226Full.CT_1100US, Ina226Full.MODE_SHUNT_BUS_CONT)
        checkTrue("configure() accepted", true)

        Thread.sleep(300)  // allow conversion with 128 averages at 1.1 ms each

        // --- Conversion ready and overflow flags ---
        ina.conversionReady()
        checkTrue("conversionReady() accepted", true)

        ina.overflow()
        checkTrue("overflow() accepted", true)

        // --- Alert ---
        ina.setAlert(Ina226Full.SOL, 0.1)
        checkTrue("setAlert(SOL, 0.1) accepted", true)

        ina.alertFlags()
        checkTrue("alertFlags() accepted", true)

        // --- Reset ---
        ina.reset()
        checkTrue("reset() accepted", true)

        // --- Shutdown / wake ---
        ina.shutdown()
        checkTrue("shutdown() accepted", true)

        Thread.sleep(10)

        ina.wake()
        checkTrue("wake() accepted", true)

        // --- Device identification ---
        val mfrId = ina.manufacturerId()
        checkTrue("manufacturerId() == 0x5449", mfrId == 0x5449)

        val dieId = ina.dieId()
        checkTrue("dieId() == 0x2260", dieId == 0x2260)
    }


    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
