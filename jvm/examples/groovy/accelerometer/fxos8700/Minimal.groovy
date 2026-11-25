///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.FXOS8700Minimal

def bus = (System.getenv('I2C_BUS') ?: '1') as int
def conn = new I2CConnection(bus, FXOS8700Minimal.I2C_ADDRESS)
try {
    def compass = new FXOS8700Minimal(conn)                      // Create FXOS8700 driver, (connection)
    10.times {
        def accel = compass.readAccel()                          // Read acceleration, () → [x, y, z] g
        def mag = compass.readMag()                              // Read magnetic field, () → [x, y, z] µT
        printf('accel x=%.4f y=%.4f z=%.4f g%n', accel[0], accel[1], accel[2])
        printf('mag   x=%.2f y=%.2f z=%.2f uT%n', mag[0], mag[1], mag[2])
        Thread.sleep(1000)
    }
} finally {
    conn.close()
}
