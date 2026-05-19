///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import groovy.transform.Field
import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina219Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

int bus  = (System.getenv('I2C_BUS')  ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x40').replaceFirst(/^0[xX]/, ''), 16)

def pi4j = Pi4J.newAutoContext()
try {
    def transport = new I2CTransport(pi4j, bus, addr)
    try {
        def ina = new Ina219Full(transport, 0.1d, 2.0d)

        // voltage() should return a value in the physically plausible range 0–36 V
        double v = ina.voltage()
        checkTrue('voltage() in range [0, 36] V', v >= 0.0 && v <= 36.0)

        // shuntVoltage() should return a finite float
        double vs = ina.shuntVoltage()
        checkTrue('shuntVoltage() is finite', !vs.isNaN() && !vs.isInfinite())

        // current() should return a finite float (signed)
        double i = ina.current()
        checkTrue('current() is finite', !i.isNaN() && !i.isInfinite())

        // power() should return a finite float
        double p = ina.power()
        checkTrue('power() is finite', !p.isNaN() && !p.isInfinite())

        // configure() should be accepted without throwing
        ina.configure(Ina219Full.BRNG_32V, Ina219Full.PGA_8,
                      Ina219Full.ADC_12BIT, Ina219Full.ADC_12BIT,
                      Ina219Full.MODE_SHUNT_BUS_CONT)
        checkTrue('configure() accepted', true)

        // conversionReady() should return a boolean
        ina.conversionReady()
        checkTrue('conversionReady() accepted', true)

        // overflow() should return a boolean
        ina.overflow()
        checkTrue('overflow() accepted', true)

        // reset() should be accepted without throwing
        ina.reset()
        checkTrue('reset() accepted', true)
        Thread.sleep(5)

        // shutdown() should be accepted without throwing
        ina.shutdown()
        checkTrue('shutdown() accepted', true)
        Thread.sleep(5)

        // wake() should restore the device
        ina.wake()
        checkTrue('wake() accepted', true)
        Thread.sleep(5)

        // After wake, voltage should still be in range
        double vAfterWake = ina.voltage()
        checkTrue('voltage() after wake in range [0, 36] V',
                  vAfterWake >= 0.0 && vAfterWake <= 36.0)

    } finally {
        transport.close()
    }
} finally {
    pi4j.shutdown()
}

println("===DONE: ${passed} passed, ${failed} failed===")
System.exit(failed == 0 ? 0 : 1)
