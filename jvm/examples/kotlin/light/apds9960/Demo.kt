///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.light.Apds9960Full

fun main() {
    I2CTransport(1, 0x39).use { transport ->                 // open I²C bus 1, device 0x39, (bus, address) → I2CTransport
        val apds = Apds9960Full(transport)                         // construct driver, (transport) → Apds9960Full

        // --- Monitor ambient light with adaptive integration time ---
        // Start with the default 200 ms integration (ATIME=0xB6). When the clear
        // channel approaches saturation, halve the integration time to prevent overflow.
        var atime = 0xB6
        apds.configureAls(atime, 1)                                // configure ALS, (atime 0-255, again 0-3) → Unit

        repeat(20) {
            while (!apds.isAlsValid()) {                           // check ALS data valid, () → Boolean
                Thread.sleep(10)
            }

            val rgbc = apds.color()                                // read all RGBC channels, () → IntArray [clear, red, green, blue]
            val c = rgbc[0]; val r = rgbc[1]; val g = rgbc[2]; val b = rgbc[3]
            val lux = -0.32466 * r + 1.57837 * g + -0.73191 * b
            println("C=$c R=$r G=$g B=$b  lux~${"%.0f".format(lux)}")

            // --- Adaptive integration: reduce time when saturated ---
            if (apds.isAlsSaturated() && atime < 0xFE) {           // check ALS saturated, () → Boolean
                atime = atime + (256 - atime) / 2
                if (atime > 0xFE) atime = 0xFE
                apds.configureAls(atime, 1)                        // configure ALS, (atime 0-255, again 0-3) → Unit
                println("[SATURATED — reducing integration time, ATIME=0x${atime.toString(16)}]")
                Thread.sleep(250)
            }

            Thread.sleep(1000)
        }
    }
}
