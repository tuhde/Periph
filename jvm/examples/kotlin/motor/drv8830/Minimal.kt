///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.motor.DRV8830Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DRV8830Minimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, device address, (bus, address=0x60) → I2CConnection
        val motor = DRV8830Minimal(connection)                              // construct driver and confirm presence, (connection) → DRV8830Minimal

        repeat(5) {
            motor.drive(3.0)                                                // Drive at regulated voltage, (voltage V, + = forward) → Unit
            Thread.sleep(2000)
            motor.drive(-3.0)                                               // Drive at regulated voltage, (voltage V, - = reverse) → Unit
            Thread.sleep(2000)
        }
        motor.stop()                                                        // Coast to standby, () → Unit
    }
}
