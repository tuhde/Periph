///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Pcf8574Full

/**
 * PCF8574 demo — button-controlled LED mirror.
 *
 * Hardware:
 *   P0–P3: LEDs (anode → VCC, cathode → pin; active-low)
 *   P4–P7: push buttons (pin → GND when pressed; internal pull-up keeps pin high)
 *
 * Every 200 ms the demo reads the full port byte, extracts the button nibble
 * (P4–P7, bits 4–7), inverts it (pressed = 0 → LED on = 0), and writes the
 * result to the output nibble (P0–P3). Prints the raw port byte and the decoded
 * states so the quasi-bidirectional read-back behavior is visible.
 */
fun main() {
    I2CTransport(1, 0x20).use { transport ->                          // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
        val chip = Pcf8574Full(transport)                              // construct full driver, (transport) → Pcf8574Full

        // --- Configure P0–P3 as outputs (LED control), P4–P7 as inputs (buttons) ---
        // Writing 0 to bits 0–3 drives the LED pins low (LEDs off initially).
        // Bits 4–7 stay at 1 (input mode) to sense button presses.
        chip.writePort(0xF0)                                           // write all 8 pins, (mask=0xF0) → Unit

        println("Running — press buttons P4–P7 to mirror to LEDs P0–P3")

        // --- Main loop: read buttons → mirror to LEDs every 200 ms ---
        // The port byte encodes both the LED drive state (bits 0–3) and the actual
        // button logic level (bits 4–7). Reading back a pin written 1 returns the
        // true external level, making quasi-bidirectional mode visible in the output.
        while (true) {
            val port = chip.readPort()                                 // read all 8 pins, () → Int bitmask

            val buttons = (port shr 4) and 0x0F   // extract P4–P7 (pressed = 0, released = 1)
            val leds    = buttons.inv() and 0x0F   // invert: button pressed → LED on (drive low = 0)
            chip.writePort(0xF0 or leds)                               // write all 8 pins, (mask) → Unit

            val btnStr = (3 downTo 0).joinToString("") { i ->
                if ((buttons shr i and 1) == 0) "X" else "."
            }
            val ledStr = (3 downTo 0).joinToString("") { i ->
                if ((leds shr i and 1) == 1) "*" else " "
            }
            println("port=0x%02X  buttons=[$btnStr]  LEDs=[$ledStr]".format(port))

            Thread.sleep(200)
        }
    }
}
