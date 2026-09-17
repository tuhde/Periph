///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3g4200dMinimal

def connection = new I2CConnection(1, 0x68)                      // open I²C bus 1, device 0x68, (bus, address=0x68) → I2CConnection
def sensor = new L3g4200dMinimal(connection, false)              // construct driver, verifies chip ID, (connection, spi=false) → L3g4200dMinimal
try {
    10.times {
        def xyz = sensor.angularRate()                            // read X/Y/Z angular rate, () → float[3] rad/s
        printf("X=%.3f Y=%.3f Z=%.3f rad/s%n", xyz[0], xyz[1], xyz[2])
        Thread.sleep(100)
    }
} finally {
    connection.close()
}
