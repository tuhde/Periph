///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.Mcp23008Full

/**
 * MCP23008 demo — LED scanner with interrupt-driven button input.
 *
 * Wire four LEDs (active-high, through 220 Ω) to GP0–GP3 and four push buttons (to GND,
 * pull-ups enabled) to GP4–GP7. A button interrupt prints INTF/INTCAP and holds the
 * matching LED (GP4→GP0, …) for one second.
 */
fun main() {
    I2CConnection(1, 0x20).use { connection ->                        // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
        val chip = Mcp23008Full(connection, 0x20)                             // construct driver, (connection, addr=0x20) → Mcp23008Full

        // --- Configure LEDs and buttons ---
        // GP0–GP3 are LED outputs; GP4–GP7 stay inputs with the 100 kΩ pull-ups on,
        // so a pressed button (to GND) reads 0.
        for (n in 0 until 4) {
            chip.pin(n).setOutput()                                           // set LED pin as output, () → Unit
        }
        chip.configurePullup(0xF0)                                            // pull-ups on GP4–GP7, (mask) → Unit

        // --- Arm interrupt-on-change on the buttons ---
        // Any button edge raises INT (or the 5 ms poll sees it); the callback prints INTF and
        // the INTCAP snapshot and holds the LED matching the pressed button for one second.
        val holdUntil = longArrayOf(0)
        val holdMask = intArrayOf(0)
        chip.enablePinInterrupt(0xF0)                                         // arm GP4–GP7, (mask) → Unit
        chip.onInterrupt({ status ->                                          // subscribe to INT, (callback[, intPin, compareToDefault]) → Unit
                    val captured = chip.readCapture()
            println("button INTF=0x%02X INTCAP=0x%02X".format(status, captured))
            holdMask[0] = (status shr 4) and 0x0F
            holdUntil[0] = System.currentTimeMillis() + 1000
        })

        // --- Scanner loop ---
        for (step in 0 until 40) {
            val leds = if (System.currentTimeMillis() < holdUntil[0]) holdMask[0] else (1 shl (step % 4))
            chip.writePort(0, leds)                                           // write LED outputs, (port=0, mask) → Unit
            val port = chip.readPort(0)                                       // read all 8 pins, (port=0) → Int bitmask
            println("  pos=%d  port=0x%02X".format(step % 4, port))
            Thread.sleep(100)
        }

        // --- Shut down ---
        chip.offInterrupt()                                                   // unsubscribe, () → Unit
        chip.writePort(0, 0x00)                                               // clear all outputs, (port=0, mask=0x00) → Unit
    }
}
