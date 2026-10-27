///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def accelConn = new I2CConnection(bus, 0x19)                   // open accelerometer, (bus, address=0x19) → I2CConnection
def magConn = new I2CConnection(bus, 0x1E)                     // open magnetometer, (bus, address=0x1E) → I2CConnection
try {
    def sensor = new LSM303Minimal(accelConn, magConn)         // construct driver and run init, (accelConnection, magConnection) → LSM303Minimal

    10.times {
        def a = sensor.readAcceleration()                      // Read acceleration, () → double[3] g
        def m = sensor.readMagnetic()                          // Read magnetic field, () → double[3] µT
        println(String.format("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) uT",
                a[0], a[1], a[2], m[0], m[1], m[2]))
        Thread.sleep(200)
    }
} finally {
    magConn.close()
    accelConn.close()
}
