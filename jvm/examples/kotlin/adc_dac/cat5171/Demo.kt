///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.CAT5171Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, CAT5171Full.DEFAULT_ADDRESS).use { connection ->                             // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
        val pot = CAT5171Full(connection)                                                           // construct driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω) → CAT5171Full
        val vdd = 3.3

        // --- Sweep the wiper across the full range ---
        // Zero scale is not 0 Ω (it is the wiper resistance) and code 255 sits one
        // LSB below the RAB end.
        for (code in 0..255 step 16) {
            pot.setWiper(code)                                                                      // Set wiper position, (code 0–255) → Unit
            val r = pot.getResistance()                                                             // Read W–B resistance, () → double Ω
            println("code ${code} -> ${r} Ω")
            Thread.sleep(200)
        }

        // --- Set a 22 kΩ rheostat ---
        // The 256 steps quantise the resistance (~390 Ω per code on a 100 kΩ part),
        // so the readback differs slightly from the request.
        pot.setResistance(22000.0)                                                                  // Set W–B resistance, (ohms Ω) → Unit
        val rheo = pot.getResistance()                                                              // Read W–B resistance, () → double Ω
        println("rheostat ${rheo} Ω, error ${rheo - 22000.0} Ω")

        // --- Shutdown, resume, midscale and reset ---
        // Shutdown opens A and shorts the wiper to B while the WCR keeps its value,
        // so resume restores the setting; midscale and reset both return to 0x80.
        val kept = pot.getWiper()                                                                   // Read wiper position, () → int 0–255
        pot.shutdown()                                                                              // Shut down, () → Unit
        val sd = pot.isShutdown()                                                                   // Query shutdown state, () → boolean
        println("shutdown: ${sd} kept ${pot.getWiper() == kept}")
        Thread.sleep(1000)
        pot.resume()                                                                                // Leave shutdown, () → Unit
        pot.midscale()                                                                              // Move wiper to midscale, () → Unit
        pot.reset()                                                                                 // Midscale reset, () → Unit

        // --- Use the wiper as a voltage divider ---
        // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
        // round(ratio × 256), so 0.999 lands on the top code 255.
        for (ratio in doubleArrayOf(0.0, 0.25, 0.5, 0.75, 0.999)) {
            pot.setRatio(ratio)                                                                     // Set divider ratio, (ratio 0.0–1.0) → Unit
            val actual = pot.getRatio()                                                             // Read divider ratio, () → double
            println("ratio ${ratio} -> ${actual} -> ${actual * vdd} V")
            Thread.sleep(500)
        }
    }
}
