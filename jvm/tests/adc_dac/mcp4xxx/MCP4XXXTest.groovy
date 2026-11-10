///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import groovy.transform.Field
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.MCP4XXXFull
import it.uhde.periph.chips.adc_dac.MCP4XXXMinimal

@Field int passed = 0
@Field int failed = 0

void checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ } else { println("FAIL $label"); failed++ }
}

String part = System.getenv('MCP4XXX_PART') ?: 'MCP4532'
int bus = Integer.parseInt(System.getenv('I2C_BUS') ?: '1')
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: Integer.toHexString(MCP4XXXMinimal.defaultAddress(part))).replaceFirst('^0[xX]', ''), 16)

def connection = new I2CConnection(bus, addr)
try {
    def pot = new MCP4XXXFull(connection, part, 10000.0d, 75.0d)
    pot.init()
    int max = pot.steps() - 1

    pot.setWiper(100 % (max + 1), 0)
    checkTrue("set/get wiper", pot.getWiper(0) == 100 % (max + 1))
    pot.setWiper(9999, 0)
    checkTrue("clamps high", pot.getWiper(0) == max)
    pot.setWiper(-5, 0)
    checkTrue("clamps low", pot.getWiper(0) == 0)
    pot.setResistance(5075.0, 0)
    checkTrue("set_resistance ~mid", Math.abs(pot.getWiper(0) - max.intdiv(2)) <= 1)
    checkTrue("get_resistance ~5075", Math.abs(pot.getResistance(0) - 5075.0) < 100.0)
    pot.setRatio(0.5, 0)
    checkTrue("get_ratio ~0.5", Math.abs(pot.getRatio(0) - 0.5) < 0.01)
    pot.setWiper(max - 3, 0)
    checkTrue("increment saturates", pot.increment(10, 0) == max)
    pot.setWiper(3, 0)
    checkTrue("decrement saturates", pot.decrement(10, 0) == 0)
    checkTrue("increment 1", pot.increment(1, 0) == 1)
    pot.setWiper(max.intdiv(4), 0)
    pot.shutdown(0)
    checkTrue("is_shutdown", pot.isShutdown(0))
    pot.resume(0)
    checkTrue("not shutdown", !pot.isShutdown(0))
    checkTrue("wiper kept across shutdown", pot.getWiper(0) == max.intdiv(4))
    pot.setTerminals(true, true, true, 0)
    checkTrue("tcon restored", (pot.getTcon() & 0xFF) == 0xFF)
    if (pot.channels() == 2) {
        pot.setBoth(10, 20)
        checkTrue("set_both", pot.getWiper(0) == 10 && pot.getWiper(1) == 20)
    }
    if (pot.isPotentiometer()) {
        pot.setWiper(0, 0)
        checkTrue("R_WA at zero scale ~RAB", Math.abs(pot.getResistanceWa(0) - 10075.0) < 100.0)
    }
    if (pot.isNonvolatile()) {
        checkTrue("status no busy", !pot.readStatus().eepromBusy)
        pot.setWiper(max.intdiv(2), 0)
        checkTrue("store_wiper", pot.storeWiper(0) == max.intdiv(2))
        checkTrue("get_nv_wiper", pot.getNvWiper(0) == max.intdiv(2))
        pot.writeData(0, 0x155)
        checkTrue("data roundtrip", pot.readData(0) == 0x155)
    }
    pot.setWiper(1, 0)
    pot.reset()
    checkTrue("reset wiper", pot.getWiper(0) == max.intdiv(2))
} finally {
    connection.close()
}

println("===DONE: $passed passed, $failed failed===")
System.exit(failed == 0 ? 0 : 1)
