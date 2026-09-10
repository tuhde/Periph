///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9930Minimal

fun main() {
    I2CConnection(1, 0x39).use { connection ->                         // Open I2C connection, (bus=1, address=0x39) → I2CConnection
        val apds = Apds9930Minimal(connection)                          // Create APDS-9930 driver, (connection) → Apds9930Minimal
                                                                       // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20
        Thread.sleep(110)
        repeat(10) {
            val lx = apds.lux()                                          // Read ambient illuminance, () → Float lx
                                                                       // IR-compensated lux via Ch0/Ch1 difference
            val p = apds.proximity()                                     // Read proximity count, () → Int count
                                                                       // 16-bit ADC value; higher = closer object
            println("lux=%.1f lx  proximity=%d".format(lx, p))
            Thread.sleep(1000)
        }
    }
}