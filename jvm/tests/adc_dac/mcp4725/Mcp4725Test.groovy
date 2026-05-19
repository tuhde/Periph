///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import groovy.transform.Field
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4725Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

int bus  = (System.getenv('I2C_BUS')  ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x60').replaceFirst(/^0[xX]/, ''), 16)

def transport   = new I2CTransport(bus, addr)
def gcTransport = new I2CTransport(bus, 0x00)
try {
    def dac = new Mcp4725Full(transport, gcTransport)

    dac.setVoltage(0.5)
    checkTrue('setVoltage(0.5) accepted', true)

    dac.setRaw(2048)
    checkTrue('setRaw(2048) accepted', true)

    dac.setVoltageEeprom(0.5)
    checkTrue('setVoltageEeprom(0.5) accepted', true)

    dac.setRawEeprom(2048)
    checkTrue('setRawEeprom(2048) accepted', true)

    Thread.sleep(50)

    def state = dac.read()
    checkTrue('read: code in range',            state.code >= 0 && state.code <= 4095)
    checkTrue('read: voltageFraction in range', state.voltageFraction >= 0.0 && state.voltageFraction <= 1.0)
    checkTrue('read: powerDown in range',       state.powerDown >= 0 && state.powerDown <= 3)
    checkTrue('read: eepromCode in range',      state.eepromCode >= 0 && state.eepromCode <= 4095)
    checkTrue('read: eepromPowerDown in range', state.eepromPowerDown >= 0 && state.eepromPowerDown <= 3)

    dac.setPowerDown(0)
    checkTrue('setPowerDown(0 normal) accepted', true)

    dac.setPowerDown(1)
    checkTrue('setPowerDown(1 1kΩ) accepted', true)

    dac.setPowerDown(2)
    checkTrue('setPowerDown(2 100kΩ) accepted', true)

    dac.setPowerDown(3)
    checkTrue('setPowerDown(3 500kΩ) accepted', true)

    dac.wakeUp()
    checkTrue('wakeUp accepted', true)

    dac.reset()
    checkTrue('reset accepted', true)

    dac.isEepromReady()
    checkTrue('isEepromReady accepted', true)

} finally {
    transport.close()
    gcTransport.close()
}

println("===DONE: ${passed} passed, ${failed} failed===")
System.exit(failed == 0 ? 0 : 1)
