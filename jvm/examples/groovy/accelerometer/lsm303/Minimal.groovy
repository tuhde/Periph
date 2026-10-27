///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Minimal

def bus = System.getenv().getOrDefault("I2C_BUS", "1") as int
def accelConn = new I2CConnection(bus, 0x19)
def magConn = new I2CConnection(bus, 0x1E)
try {
    def chip = new LSM303Minimal(accelConn, magConn)            // Create LSM303 driver, (accelConnection, magConnection) → void
    for (i in 0..<10) {
        def a = chip.readAcceleration()                         // Read 3-axis acceleration, () → double[] g
        def m = chip.readMagnetic()                             // Read 3-axis magnetic field, () → double[] µT
        printf("a=(%.3f, %.3f, %.3f) g  m=(%.1f, %.1f, %.1f) µT%n", a[0], a[1], a[2], m[0], m[1], m[2])
        Thread.sleep(100)
    }
} finally {
    accelConn.close()
    magConn.close()
}
