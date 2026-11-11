///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA9536Full

/**
 * PCA9536 demo — blinking LEDs with polled button input.
 *
 * Wire two active-low LEDs (anode to VCC through 220 Ω; the outputs sink up to 25 mA) to P0–P1 and
 * two push buttons (to GND) to P2–P3 — the chip's built-in weak pull-up holds an open button
 * input high, so no external resistor is needed. The LEDs blink alternately; a button change
 * prints the changed mask and the port state and holds the matching LED (P2→P0, P3→P1) for
 * one second.
 */
fun main() {
    I2CConnection(1, 0x41).use { connection ->                        // open I²C bus 1, device 0x41, (bus, address) → I2CConnection
        val chip = PCA9536Full(connection, 0x41)                         // construct driver, (connection, addr=0x41) → PCA9536Full

        // --- Configure LEDs and buttons ---
        // P0–P1 are active-low LED outputs (0 lights the LED); P2–P3 stay inputs and rely on the
        // built-in weak pull-up, so a pressed button (to GND) reads 0.
        chip.writePort(0, 0x0F)                                           // turn all LEDs off, (port=0, mask=0x0F) → Unit
        chip.setDirection(0x0C)                                           // P0–P1 outputs, P2–P3 inputs, (mask) → Unit

        // --- Watch for input changes ---
        // The chip has no INT pin: a 5 ms polling thread reports changes; the callback prints the
        // changed mask and the port state and holds the LED matching the pressed button for one second.
        var holdUntil = 0L
        var holdMask = 0x03
        chip.watchChanges({ changed ->                                    // start polling for changes, (callback, intervalS=0.005) → Unit
            val port = chip.readPort(0)
            println("button changed=0x%X port=0x%X".format(changed, port))
            holdMask = (changed shr 2).inv() and 0x03
            holdUntil = System.currentTimeMillis() + 1000
        })

        // --- Blink loop ---
        for (step in 0 until 40) {
            val leds = if (System.currentTimeMillis() < holdUntil) holdMask else (if (step % 2 == 0) 0x02 else 0x01)
            chip.writePort(0, 0x0C or leds)                               // write LED outputs, (port=0, mask) → Unit
            Thread.sleep(100)
        }

        // --- Shut down ---
        chip.unwatchChanges()                                             // stop polling, () → Unit
        chip.writePort(0, 0x0F)                                           // turn all LEDs off, (port=0, mask=0x0F) → Unit
    }
}
