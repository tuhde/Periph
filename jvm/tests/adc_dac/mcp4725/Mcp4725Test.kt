///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4725Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x60

    val pi4j = Pi4J.newAutoContext()
    try {
        I2CTransport(pi4j, bus, addr).use { transport ->
        I2CTransport(pi4j, bus, 0x00).use { gcTransport ->

            val dac = Mcp4725Full(transport, gcTransport)

            dac.setVoltage(0.5)
            checkTrue("setVoltage(0.5) accepted", true)

            dac.setRaw(2048)
            checkTrue("setRaw(2048) accepted", true)

            dac.setVoltageEeprom(0.5)
            checkTrue("setVoltageEeprom(0.5) accepted", true)

            dac.setRawEeprom(2048)
            checkTrue("setRawEeprom(2048) accepted", true)

            Thread.sleep(50)

            val state = dac.read()
            checkTrue("read: code in range",            state.code in 0..4095)
            checkTrue("read: voltageFraction in range", state.voltageFraction in 0.0..1.0)
            checkTrue("read: powerDown in range",       state.powerDown in 0..3)
            checkTrue("read: eepromCode in range",      state.eepromCode in 0..4095)
            checkTrue("read: eepromPowerDown in range", state.eepromPowerDown in 0..3)

            dac.setPowerDown(0)
            checkTrue("setPowerDown(0 normal) accepted", true)

            dac.setPowerDown(1)
            checkTrue("setPowerDown(1 1kΩ) accepted", true)

            dac.setPowerDown(2)
            checkTrue("setPowerDown(2 100kΩ) accepted", true)

            dac.setPowerDown(3)
            checkTrue("setPowerDown(3 500kΩ) accepted", true)

            dac.wakeUp()
            checkTrue("wakeUp accepted", true)

            dac.reset()
            checkTrue("reset accepted", true)

            dac.isEepromReady()
            checkTrue("isEepromReady accepted", true)
        }}
    } finally {
        pi4j.shutdown()
    }

    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
