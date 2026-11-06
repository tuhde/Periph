///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-kotlin:1.3.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5248Full

fun main() {
    I2CConnection(1, 0x2C).use { connection ->                   // open I²C bus 1, device 0x2C, (bus, address) → I2CConnection
        val pot = AD5248Full(connection, 10000.0, 160.0)         // construct driver, (connection, rAb=10000 Ω, rWiper=160 Ω) → AD5248Full

        // --- Sweep channel 0 across its full range ---
        // Programmable gain / level trim: step the wiper from code 0 to 255 in steps of 16
        // and print the resulting W-B resistance. Zero scale is not 0 Ω (about 2*RW = 320 Ω).
        for (code in 0..255 step 16) {
            pot.setWiper(0, code)                                // set channel 0 wiper, (channel 0-1, code 0-255) → void
            val ohms = pot.getResistance(0)                      // read W-B resistance, (channel 0-1) → double Ω
            println("code=%3d  R_WB=%7.1f ohm".format(code, ohms))
            Thread.sleep(100)
        }
        // --- Set channel 1 to 2.2 kΩ and report the quantisation error ---
        // 256 positions over 10 kΩ give a step of about 39 Ω, so the result is the nearest code.
        pot.setResistance(1, 2200.0)                             // set channel 1 to 2.2 kΩ, (channel 0-1, ohms) → void
        val actual = pot.getResistance(1)                        // read back W-B resistance, (channel 0-1) → double Ω
        println("ch1 target=2200 ohm  actual=%.1f ohm  error=%.1f ohm".format(actual, actual - 2200.0))
        // --- Shutdown and resume channel 1 ---
        // Shutdown opens terminal A and shorts the wiper to B (about 0 Ω) without changing the RDAC.
        pot.shutdown(1)                                          // shut channel 1 down, (channel 0-1) → void
        val down = pot.isShutdown(1)                             // query driver-tracked state, (channel 0-1) → boolean
        println("ch1 shutdown=%s  code=%d".format(down, pot.getWiper(1)))
        Thread.sleep(1000)
        pot.resume(1)                                            // resume channel 1, (channel 0-1) → void
        // --- Restore the power-up preset ---
        pot.reset()                                              // reset both channels to midscale, () → void
        println("after reset: ch0=%d ch1=%d".format(pot.getWiper(0), pot.getWiper(1)))
    }
}
