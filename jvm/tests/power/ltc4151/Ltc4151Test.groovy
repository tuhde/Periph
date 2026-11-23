///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-groovy:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ltc4151Full

int passed = 0
int failed = 0

def checkTrue = { String label, boolean condition ->
    if (condition) { println "PASS ${label}"; passed++ }
    else           { println "FAIL ${label}"; failed++ }
}

int bus  = (System.getenv('I2C_BUS') ?: '1') as int
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x6A').replaceFirst('^0[xX]', ''), 16)

def connection = new I2CConnection(bus, addr)
try {
    def ltc = new Ltc4151Full(connection, 0.02d)

    double v = ltc.voltage()
    checkTrue('voltage() in range 7-80 V', v >= 7.0d && v <= 80.0d)
    double c = ltc.current()
    checkTrue('current() in range', c >= 0.0d && c <= 4.096d)
    double p = ltc.power()
    checkTrue('power() in range', p >= 0.0d && p <= 330.0d)
    double sv = ltc.senseVoltage()
    checkTrue('senseVoltage() in range', sv >= 0.0d && sv <= 0.08192d)
    double av = ltc.adinVoltage()
    checkTrue('adinVoltage() in range', av >= 0.0d && av <= 2.048d)
    checkTrue('readAll() vin in range', ltc.readAll().vin >= 7.0d)
    int raw = ltc.readRaw(Ltc4151Full.VIN)
    checkTrue('readRaw(VIN) in range', raw > 0 && raw < 4096)

    checkTrue('readControl() default 0x0C', ltc.readControl() == 0x0C)
    checkTrue('not in snapshot mode', !ltc.isSnapshotMode())
    double s = ltc.snapshot(Ltc4151Full.VIN)
    checkTrue('snapshot(VIN) in range', s >= 7.0d && s <= 80.0d)
    checkTrue('back in continuous scan', !ltc.isSnapshotMode())

    ltc.setStuckBusTimer(false)
    checkTrue('stuck-bus timer off', ltc.readControl() == 0x08)
    ltc.setStuckBusTimer(true)
    checkTrue('stuck-bus timer on', ltc.readControl() == 0x0C)
} finally {
    connection.close()
}

println "===DONE: ${passed} passed, ${failed} failed==="
System.exit(failed == 0 ? 0 : 1)
