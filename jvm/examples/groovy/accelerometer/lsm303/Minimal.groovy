///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Minimal

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
// The module presents two I²C slaves on the same bus: accelerometer 0x19
// and magnetometer 0x1E — both connections are required.
def accelConn = new I2CConnection(bus, 0x19)
def magConn   = new I2CConnection(bus, 0x1E)
try {
    def compass = new LSM303Minimal(accelConn, magConn)          // Create LSM303 driver, (accel_connection, mag_connection)
    10.times {
        def a = compass.readAcceleration()                       // Read 3-axis acceleration, () → [g, g, g]
                                                                 // burst-reads 0x28–0x2D with auto-increment, 1 mg/LSB at ±2 g
        def m = compass.readMagnetic()                           // Read 3-axis magnetic field, () → [µT, µT, µT]
                                                                 // on-wire order X, Z, Y high-byte-first, reordered to X, Y, Z
        printf("a=(%+.3f, %+.3f, %+.3f) g  m=(%+.1f, %+.1f, %+.1f) uT%n",
                a[0], a[1], a[2], m[0], m[1], m[2])
        Thread.sleep(67)
    }
} finally {
    accelConn.close()
    magConn.close()
}
