///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-groovy:1.3.2

import groovy.transform.Field
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5248Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

int bus  = (System.getenv('I2C_BUS')  ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x2C').replaceFirst(/^0[xX]/, ''), 16)

def connection = new I2CConnection(bus, addr)
try {
    def chip = new AD5248Full(connection, 10000.0d, 160.0d)

    chip.setWiper(0, 100)
    checkTrue("set/get wiper ch0", chip.getWiper(0) == 100)
    chip.setWiper(1, 200)
    checkTrue("set/get wiper ch1", chip.getWiper(1) == 200)
    checkTrue("ch0 unchanged by ch1 write", chip.getWiper(0) == 100)
    chip.setResistance(0, 5320.0)
    checkTrue("set_resistance midscale code", chip.getWiper(0) == 128)
    checkTrue("get_resistance ~5320", Math.abs(chip.getResistance(0) - 5320.0) < 1.0)
    chip.setBoth(10, 20)
    checkTrue("set_both ch0", chip.getWiper(0) == 10)
    checkTrue("set_both ch1", chip.getWiper(1) == 20)
    chip.setRatio(0, 0.5)
    checkTrue("get_ratio ~0.5", Math.abs(chip.getRatio(0) - 0.5) < 0.01)
    chip.setWiper(0, 250)
    checkTrue("increment clamps", chip.increment(0, 10) == 255)
    chip.setWiper(1, 77)
    chip.shutdown(1)
    checkTrue("is_shutdown", chip.isShutdown(1))
    chip.resume(1)
    checkTrue("RDAC kept across shutdown", chip.getWiper(1) == 77)
    chip.reset()
    checkTrue("reset ch0", chip.getWiper(0) == 0x80)
    checkTrue("reset ch1", chip.getWiper(1) == 0x80)
} finally {
    connection.close()
}

println("===DONE: $passed passed, $failed failed===")
System.exit(failed == 0 ? 0 : 1)
