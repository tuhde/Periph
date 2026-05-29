///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import groovy.transform.Field
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Aht21Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

int bus  = (System.getenv('I2C_BUS')  ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x38').replaceFirst(/^0[xX]/, ''), 16)

def transport = new I2CTransport(bus, addr)
try {
    def aht = new Aht21Full(transport)

    checkTrue('isCalibrated', aht.isCalibrated())
    checkTrue('not busy at idle', !aht.isBusy())

    double[] r = aht.read()
    checkTrue('temperature range', r[0] >= -40.0d && r[0] <= 120.0d)
    checkTrue('humidity range', r[1] >= 0.0d && r[1] <= 100.0d)

    double tr = aht.readTemperature()
    checkTrue('readTemperature range', tr >= -40.0d && tr <= 120.0d)

    double hr = aht.readHumidity()
    checkTrue('readHumidity range', hr >= 0.0d && hr <= 100.0d)

    double[] rc = aht.readWithCrc()
    checkTrue('crc_ok', rc[2] > 0.5d)
    checkTrue('crc temperature range', rc[0] >= -40.0d && rc[0] <= 120.0d)
    checkTrue('crc humidity range', rc[1] >= 0.0d && rc[1] <= 100.0d)

    aht.softReset()
    Thread.sleep(50)
    checkTrue('calibrated after reset', aht.isCalibrated())

    double[] r2 = aht.read()
    checkTrue('read after reset: temperature range', r2[0] >= -40.0d && r2[0] <= 120.0d)
    checkTrue('read after reset: humidity range', r2[1] >= 0.0d && r2[1] <= 100.0d)

} finally {
    transport.close()
}

println("===DONE: ${passed} passed, ${failed} failed===")
System.exit(failed == 0 ? 0 : 1)
