///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA6408AFull

/**
 * PCA6408A demo — LED scanner with interrupt-driven button input.
 *
 * Wire four active-low LEDs (anode to VDD(P) through 220 Ω) to P0–P3 and four push buttons
 * (to GND, with external pull-ups: the chip has none) to P4–P7. A button interrupt prints the
 * changed mask and the port state and holds the matching LED (P4→P0, …) for one second.
 */
fun main() {
    I2CConnection(1, 0x20).use { connection ->                        // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
        val chip = PCA6408AFull(connection, 0x20)                         // construct driver, (connection, addr=0x20) → PCA6408AFull

        // --- Configure LEDs and buttons ---
        // P0–P3 are active-low LED outputs (0 lights the LED); P4–P7 stay inputs and rely on
        // external pull-ups (the chip has none), so a pressed button (to GND) reads 0.
        chip.writePort(0, 0xFF)                                           // turn all LEDs off, (port=0, mask=0xFF) → Unit
        chip.setDirection(0xF0)                                           // P0–P3 outputs, P4–P7 inputs, (mask) → Unit

        // --- Subscribe to input changes ---
        // Any button edge raises INT (or the 5 ms poll sees it); the callback prints the changed
        // mask and the port state and holds the LED matching the pressed button for one second.
        var holdUntil = 0L
        var holdMask = 0x0F
        chip.onInterrupt({ changed ->                                     // subscribe to INT, (callback, intPin=null) → Unit
            val port = chip.readPort(0)
            println("button changed=0x%02X port=0x%02X".format(changed, port))
            holdMask = (changed shr 4).inv() and 0x0F
            holdUntil = System.currentTimeMillis() + 1000
        })

        // --- Scanner loop ---
        for (step in 0 until 40) {
            val leds = if (System.currentTimeMillis() < holdUntil) holdMask else ((1 shl (step % 4)).inv() and 0x0F)
            chip.writePort(0, 0xF0 or leds)                               // write LED outputs, (port=0, mask) → Unit
            val port = chip.readPort(0)                                   // read all 8 pins, (port=0) → Int bitmask
            println("  pos=%d  port=0x%02X".format(step % 4, port))
            Thread.sleep(100)
        }

        // --- Shut down ---
        chip.offInterrupt()                                               // unsubscribe, () → Unit
        chip.writePort(0, 0xFF)                                           // turn all LEDs off, (port=0, mask=0xFF) → Unit
    }
}
