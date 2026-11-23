///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-kotlin:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ina260Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x40

    I2CConnection(bus, addr).use { connection ->

        val ina = Ina260Full(connection)

        // --- Basic measurements ---
        val v = ina.voltage()
        checkTrue("voltage() in range 0–36 V", v in 0.0..36.0)


        val c = ina.current()
        checkTrue("current() is finite", c.isFinite())

        val p = ina.power()
        checkTrue("power() is finite", p.isFinite())

        // --- Configuration ---
        ina.configure(Ina260Full.AVG_128, Ina260Full.CT_1100US,
                      Ina260Full.CT_1100US, Ina260Full.MODE_SHUNT_BUS_CONT)
        checkTrue("configure() accepted", true)

        Thread.sleep(300)  // allow conversion with 128 averages at 1.1 ms each

        // --- Conversion ready and overflow flags ---
        ina.conversionReady()
        checkTrue("conversionReady() accepted", true)

        ina.overflow()
        checkTrue("overflow() accepted", true)

        // --- Alert ---
        ina.setAlert(Ina260Full.OCL, 5.0)
        checkTrue("setAlert(OCL, 5.0) accepted", true)

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
        checkTrue("(dieId() & 0xFFF0) == 0x2270", (dieId and 0xFFF0) == 0x2270)
    }


    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
