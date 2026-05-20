///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import groovy.transform.Field
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp180Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

int bus = (System.getenv('I2C_BUS') ?: '1').toInteger()
// BMP180 has a fixed I²C address of 0x77; I2C_ADDR is intentionally ignored.
int addr = 0x77

def transport = new I2CTransport(bus, addr)
try {
    def sensor = new Bmp180Full(transport)

    // temperature() — must be in sensor operating range
    double t = sensor.temperature()
    checkTrue('temperature() in range [-20, 85] °C', t >= -20.0 && t <= 85.0)

    // pressure() — valid range per datasheet
    double p = sensor.pressure()
    checkTrue('pressure() in range [300, 1100] hPa', p >= 300.0 && p <= 1100.0)

    // altitude() — just checks it returns a double without throwing
    double alt = sensor.altitude()
    checkTrue('altitude() returns double', !alt.isNaN() && !alt.isInfinite())

    // seaLevelPressure(0.0) — must yield a positive value
    double slp = sensor.seaLevelPressure(0.0)
    checkTrue('seaLevelPressure(0.0) > 0', slp > 0.0)

    // chipId() — must return 0x55
    int id = sensor.chipId()
    checkTrue('chipId() == 0x55', id == 0x55)

    // setOversampling(3) — must be accepted without exception
    sensor.setOversampling(3)
    checkTrue('setOversampling(3) accepted', true)

    // oversampling() — must reflect the value just set
    int oss = sensor.oversampling()
    checkTrue('oversampling() == 3', oss == 3)

    // reset() — must complete without exception and keep sensor functional
    sensor.reset()
    checkTrue('reset() accepted', true)

    // verify sensor still works after reset
    double tAfter = sensor.temperature()
    checkTrue('temperature() after reset in range [-20, 85] °C',
              tAfter >= -20.0 && tAfter <= 85.0)

} finally {
    transport.close()
}

println("===DONE: ${passed} passed, ${failed} failed===")
System.exit(failed == 0 ? 0 : 1)
