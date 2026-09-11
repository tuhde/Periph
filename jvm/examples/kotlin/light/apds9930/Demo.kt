///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9930Full

const val DIM_LUX_THRESHOLD = 10.0f
const val PROX_SCREEN_OFF   = 400

fun main() {
    I2CConnection(1, 0x39).use { connection ->                         // Open I2C connection, (bus=1, address=0x39) → I2CConnection
        val apds = Apds9930Full(connection)                              // Create APDS-9930 Full driver, (connection) → Apds9930Full
                                                                       // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive
        Thread.sleep(110)

        // --- Sample lux and proximity once per second for 30 cycles ---
        for (i in 0 until 30) {
            Thread.sleep(1000)
            val lx = apds.lux()                                            // Read ambient illuminance, () → Float lx
            val p = apds.proximity()                                       // Read proximity count, () → Int count
            println("lux=%.1f lx  proximity=%d".format(lx, p))
            if (lx < DIM_LUX_THRESHOLD) println("  -> dim backlight")
            if (p > PROX_SCREEN_OFF)     println("  -> disable screen")
        }
    }
}