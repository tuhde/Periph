///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import groovy.transform.Field
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina226Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

int bus  = (System.getenv('I2C_BUS')  ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x40').replaceFirst(/^0[xX]/, ''), 16)

def transport = new I2CTransport(bus, addr)
try {
    def ina = new Ina226Full(transport, 0.1d, 2.0d)

    // --- Basic measurements ---
    double v = ina.voltage()
    checkTrue('voltage() in range 0–36 V', v >= 0.0d && v <= 36.0d)

    double vs = ina.shuntVoltage()
    checkTrue('shuntVoltage() is finite', !vs.isNaN() && !vs.isInfinite())

    double c = ina.current()
    checkTrue('current() is finite', !c.isNaN() && !c.isInfinite())

    double p = ina.power()
    checkTrue('power() is finite', !p.isNaN() && !p.isInfinite())

    // --- Configuration ---
    ina.configure(Ina226Full.AVG_128, Ina226Full.CT_1100US,
                  Ina226Full.CT_1100US, Ina226Full.MODE_SHUNT_BUS_CONT)
    checkTrue('configure() accepted', true)

    Thread.sleep(300)  // allow conversion with 128 averages at 1.1 ms each

    // --- Conversion ready and overflow flags ---
    ina.conversionReady()
    checkTrue('conversionReady() accepted', true)

    ina.overflow()
    checkTrue('overflow() accepted', true)

    // --- Alert ---
    ina.setAlert(Ina226Full.SOL, 0.1d)
    checkTrue('setAlert(SOL, 0.1) accepted', true)

    ina.alertFlags()
    checkTrue('alertFlags() accepted', true)

    // --- Reset ---
    ina.reset()
    checkTrue('reset() accepted', true)

    // --- Shutdown / wake ---
    ina.shutdown()
    checkTrue('shutdown() accepted', true)

    Thread.sleep(10)

    ina.wake()
    checkTrue('wake() accepted', true)

    // --- Device identification ---
    int mfrId = ina.manufacturerId()
    checkTrue('manufacturerId() == 0x5449', mfrId == 0x5449)

    int dieId = ina.dieId()
    checkTrue('dieId() == 0x2260', dieId == 0x2260)

} finally {
    transport.close()
}

println("===DONE: ${passed} passed, ${failed} failed===")
System.exit(failed == 0 ? 0 : 1)
