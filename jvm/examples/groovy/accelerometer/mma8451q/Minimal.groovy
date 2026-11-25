///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.chips.accelerometer.MMA8451QMinimal
import it.uhde.periph.connection.I2CConnection

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
def addr = System.getenv("I2C_ADDR")?.replaceFirst("^0[xX]", "")?.toInteger(16) ?: 0x1C
new I2CConnection(bus, addr).withCloseable { connection ->
    def accel = new MMA8451QMinimal(connection)  // Create MMA8451Q driver, (connection)
    10.times {
        def xyz = accel.read()                   // Read 3-axis acceleration, () → [g, g, g]
        printf("x=%.3f y=%.3f z=%.3f g%n", xyz[0], xyz[1], xyz[2])
        Thread.sleep(100)
    }
}
