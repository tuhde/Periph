///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import groovy.transform.Field
import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina3221Full

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
        def ina = new Ina3221Full(transport)

        // --- Minimal methods: all three channels ---
        (1..3).each { ch ->
            def v = ina.voltage(ch)
            checkTrue("voltage(ch${ch}) in 0–26 V", v >= 0.0 && v <= 26.0)

            def sv = ina.shuntVoltage(ch)
            checkTrue("shuntVoltage(ch${ch}) is finite", sv != Double.NaN && !sv.infinite)

            def i = ina.current(ch)
            checkTrue("current(ch${ch}) is finite", i != Double.NaN && !i.infinite)

            def p = ina.power(ch)
            checkTrue("power(ch${ch}) is finite", p != Double.NaN && !p.infinite)
        }

        // --- configure() ---
        ina.configure(1, 4, 4, Ina3221Full.MODE_SHUNT_BUS_CONT)
        checkTrue('configure() accepted', true)

        // --- enableChannel / channelEnabled ---
        ina.enableChannel(1, true)
        checkTrue('enableChannel(1, true) accepted', true)

        def en = ina.channelEnabled(1)
        checkTrue('channelEnabled(1) returns boolean', en instanceof Boolean)

        // --- conversionReady ---
        def cvrf = ina.conversionReady()
        checkTrue('conversionReady() returns boolean', cvrf instanceof Boolean)

        // --- Alert limits ---
        ina.setCriticalAlert(1, 0.1)
        checkTrue('setCriticalAlert(1, 0.1) accepted', true)

        ina.setWarningAlert(1, 0.08)
        checkTrue('setWarningAlert(1, 0.08) accepted', true)

        // --- alertFlags ---
        def flags = ina.alertFlags()
        checkTrue('alertFlags() in 0–0xFFFF', flags >= 0 && flags <= 0xFFFF)

        // --- power-valid limits ---
        ina.setPowerValidLimits(5.5, 4.5)
        checkTrue('setPowerValidLimits(5.5, 4.5) accepted', true)

        // --- summation ---
        ina.setSummationChannels([1, 2, 3] as int[], 0.1)
        checkTrue('setSummationChannels([1,2,3], 0.1) accepted', true)

        def sumV = ina.summationValue()
        checkTrue('summationValue() is finite', sumV != Double.NaN && !sumV.infinite)

        // --- powerValid ---
        def pv = ina.powerValid()
        checkTrue('powerValid() returns boolean', pv instanceof Boolean)

        // --- manufacturerId / dieId ---
        def mfr = ina.manufacturerId()
        checkTrue('manufacturerId() == 0x5449', mfr == 0x5449)

        def die = ina.dieId()
        checkTrue('dieId() == 0x3220', die == 0x3220)

        // --- shutdown / wake ---
        ina.shutdown()
        checkTrue('shutdown() accepted', true)

        ina.wake()
        checkTrue('wake() accepted', true)

        // --- reset ---
        ina.reset()
        checkTrue('reset() accepted', true)

    } finally {
        transport.close()
    }
} finally {
    pi4j.shutdown()
}

println("===DONE: ${passed} passed, ${failed} failed===")
System.exit(failed == 0 ? 0 : 1)
