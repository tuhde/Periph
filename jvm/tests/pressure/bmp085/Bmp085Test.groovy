///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp085Full

int bus = System.getenv().getOrDefault("I2C_BUS", "1") as int
int addr = 0x77

int passed = 0
int failed = 0

void checkTrue(String label, boolean condition) {
    if (condition) { println "PASS $label"; passed++ }
    else { println "FAIL $label"; failed++ }
}

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new Bmp085Full(conn)

    double t = sensor.temperature()
    checkTrue("temperature() in range [-20, 85] °C", t >= -20.0 && t <= 85.0)

    double p = sensor.pressure()
    checkTrue("pressure() in range [30000, 110000] Pa", p >= 30000.0 && p <= 110000.0)

    double alt = sensor.altitude()
    checkTrue("altitude() returns double", Double.isFinite(alt))

    double slp = sensor.seaLevelPressure(0.0)
    checkTrue("seaLevelPressure(0.0) > 0", slp > 0.0)

    int id = sensor.chipId()
    checkTrue("chipId() == 0x55", id == 0x55)

    sensor.setOversampling(3)
    checkTrue("setOversampling(3) accepted", true)

    int oss = sensor.oversampling()
    checkTrue("oversampling() == 3", oss == 3)

    sensor.reset()
    checkTrue("reset() accepted", true)

    double tAfter = sensor.temperature()
    checkTrue("temperature() after reset in range [-20, 85] °C",
              tAfter >= -20.0 && tAfter <= 85.0)
} finally {
    conn.close()
}

printf "===DONE: %d passed, %d failed===%n", passed, failed
System.exit(failed == 0 ? 0 : 1)