///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina219Full

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

        val ina = Ina219Full(transport, 0.1, 2.0)

        // voltage() should return a value in the physically plausible range 0–36 V
        val v = ina.voltage()
        checkTrue("voltage() in range [0, 36] V", v >= 0.0 && v <= 36.0)

        // shuntVoltage() should return a finite float
        val vs = ina.shuntVoltage()
        checkTrue("shuntVoltage() is finite", vs.isFinite())

        // current() should return a finite float (signed)
        val i = ina.current()
        checkTrue("current() is finite", i.isFinite())

        // power() should return a finite float
        val p = ina.power()
        checkTrue("power() is finite", p.isFinite())

        // configure() should be accepted without throwing
        ina.configure(Ina219Full.BRNG_32V, Ina219Full.PGA_8,
                      Ina219Full.ADC_12BIT, Ina219Full.ADC_12BIT,
                      Ina219Full.MODE_SHUNT_BUS_CONT)
        checkTrue("configure() accepted", true)

        // conversionReady() should return a boolean
        ina.conversionReady()
        checkTrue("conversionReady() accepted", true)

        // overflow() should return a boolean
        ina.overflow()
        checkTrue("overflow() accepted", true)

        // reset() should be accepted without throwing
        ina.reset()
        checkTrue("reset() accepted", true)
        Thread.sleep(5)

        // shutdown() should be accepted without throwing
        ina.shutdown()
        checkTrue("shutdown() accepted", true)
        Thread.sleep(5)

        // wake() should restore the device
        ina.wake()
        checkTrue("wake() accepted", true)
        Thread.sleep(5)

        // After wake, voltage should still be in range
        val vAfterWake = ina.voltage()
        checkTrue("voltage() after wake in range [0, 36] V",
                  vAfterWake >= 0.0 && vAfterWake <= 36.0)
    }


    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
