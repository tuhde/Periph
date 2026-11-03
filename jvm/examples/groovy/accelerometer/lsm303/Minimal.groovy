///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Minimal

def bus = (System.getenv('I2C_BUS') ?: '1') as int
def accelConn = new I2CConnection(bus, LSM303Minimal.I2C_ADDRESS_ACCEL)
def magConn = new I2CConnection(bus, LSM303Minimal.I2C_ADDRESS_MAG)
try {
    def compass = new LSM303Minimal(accelConn, magConn)     // Create LSM303 driver, (accel_connection, mag_connection)
    10.times {
        def accel = compass.readAcceleration()               // Read acceleration, () → [g, g, g]
        def mag = compass.readMagnetic()                      // Read magnetic field, () → [µT, µT, µT]
        printf('accel x=%.4f y=%.4f z=%.4f g%n', accel[0], accel[1], accel[2])
        printf('mag   x=%.2f y=%.2f z=%.2f uT%n', mag[0], mag[1], mag[2])
        Thread.sleep(1000)
    }
} finally {
    accelConn.close()
    magConn.close()
}
