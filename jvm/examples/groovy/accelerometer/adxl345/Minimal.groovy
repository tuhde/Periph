///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.Adxl345Minimal

def bus  = System.getenv("I2C_BUS")  ? Integer.parseInt(System.getenv("I2C_BUS")) : 1
def addr = System.getenv("I2C_ADDR") ? Integer.parseInt(System.getenv("I2C_ADDR").replaceFirst("^0[xX]", ""), 16) : 0x53

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Adxl345Minimal(connection)                         // construct driver and verify DEVID, (connection) → Adxl345Minimal

    10.times {
        def xyz = sensor.read()                                        // Read 3-axis acceleration, () → double[] g, g, g
        println(String.format("x=%.3f y=%.3f z=%.3f g", xyz[0], xyz[1], xyz[2]))
        Thread.sleep(100)
    }
} finally {
    connection.close()
}