///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA6416AFull

/**
 * PCA6416A demo — LED scanner with interrupt-driven button input.
 *
 * Wire eight active-low LEDs (anode to VDD(P) through 220 Ω) to P0_0–P0_7 and eight push buttons
 * (to GND, with external pull-ups: the chip has none) to P1_0–P1_7. A button interrupt prints the
 * changed mask and the pin state and holds the matching LED (P1_0→P0_0, …) for one second.
 */
fun main() {
    I2CConnection(1, 0x20).use { connection ->                        // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
        val chip = PCA6416AFull(connection, 0x20)                         // construct driver, (connection, addr=0x20) → PCA6416AFull

        // --- Configure LEDs and buttons ---
        // Port 0 holds the active-low LED outputs (0 lights the LED); port 1 stays input and relies on
        // external pull-ups (the chip has none), so a pressed button (to GND) reads 0. Polarity
        // inversion makes a pressed button read as 1.
        chip.writeAll(0xFFFF)                                           // turn all LEDs off, (value=0xFFFF) → Unit
        chip.setDirection(0xFF00)                                           // port 0 outputs, port 1 inputs, (mask) → Unit
        chip.configurePolarity(0xFF00)                                      // invert port 1 inputs, (mask) → Unit

        // --- Subscribe to input changes ---
        // Any button edge raises INT (or the 5 ms poll sees it); the callback prints the 16-bit changed
        // mask and the pin state and holds the LED matching the pressed button for one second.
        var holdUntil = 0L
        var holdMask = 0xFF
        chip.onInterrupt({ changed ->                                     // subscribe to INT, (callback, intPin=null) → Unit
            val all = chip.readAll()
            println("button changed=0x%04X all=0x%04X".format(changed, port))
            holdMask = (changed shr 4).inv() and 0xFF
            holdUntil = System.currentTimeMillis() + 1000
        })

        // --- Scanner loop ---
        for (step in 0 until 40) {
            val leds = if (System.currentTimeMillis() < holdUntil) holdMask else ((1 shl (step % 8)).inv() and 0xFF)
            chip.writeAll(0xFF00 or leds)                               // write LED outputs, (value) → Unit
            val all = chip.readAll()                                   // read all 16 pins, () → Int
            println("  pos=%d  all=0x%04X".format(step % 8, port))
            Thread.sleep(100)
        }

        // --- Shut down ---
        chip.offInterrupt()                                               // unsubscribe, () → Unit
        chip.writeAll(0xFFFF)                                           // turn all LEDs off, (value=0xFFFF) → Unit
    }
}
