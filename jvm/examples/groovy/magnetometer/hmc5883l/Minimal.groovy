///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.magnetometer.Hmc5883lMinimal

def connection = new I2CConnection(1, 0x1E)
def hmc5883l = new Hmc5883lMinimal(connection)

10.times {
    def (x, y, z) = hmc5883l.magneticField()
    printf "X=%.6f T  Y=%.6f T  Z=%.6f T\n", x ?: Double.NaN, y ?: Double.NaN, z ?: Double.NaN
    Thread.sleep(1000)
}

connection.close()