///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import groovy.transform.Field
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ina260Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

int bus  = (System.getenv('I2C_BUS')  ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x40').replaceFirst(/^0[xX]/, ''), 16)

def connection = new I2CConnection(bus, addr)
try {
    def ina = new Ina260Full(connection)

    // --- Basic measurements ---
    double v = ina.voltage()
    checkTrue('voltage() in range 0–36 V', v >= 0.0d && v <= 36.0d)


    double c = ina.current()
    checkTrue('current() is finite', !c.isNaN() && !c.isInfinite())

    double p = ina.power()
    checkTrue('power() is finite', !p.isNaN() && !p.isInfinite())

    // --- Configuration ---
    ina.configure(Ina260Full.AVG_128, Ina260Full.CT_1100US,
                  Ina260Full.CT_1100US, Ina260Full.MODE_SHUNT_BUS_CONT)
    checkTrue('configure() accepted', true)

    Thread.sleep(300)  // allow conversion with 128 averages at 1.1 ms each

    // --- Conversion ready and overflow flags ---
    ina.conversionReady()
    checkTrue('conversionReady() accepted', true)

    ina.overflow()
    checkTrue('overflow() accepted', true)

    // --- Alert ---
    ina.setAlert(Ina260Full.OCL, 5.0d)
    checkTrue('setAlert(OCL, 5.0) accepted', true)

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
    checkTrue('(dieId() & 0xFFF0) == 0x2270', (dieId & 0xFFF0) == 0x2270)

} finally {
    connection.close()
}

println("===DONE: ${passed} passed, ${failed} failed===")
System.exit(failed == 0 ? 0 : 1)
