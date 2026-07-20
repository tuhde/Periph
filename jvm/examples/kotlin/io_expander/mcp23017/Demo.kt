///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Mcp23017Full

/**
 * MCP23017 demo — Knight Rider scanner with button override.
 *
 * Hardware:
 *   GPA0–GPA6: seven LEDs (anode → VCC via 220Ω, cathode → pin; active-high)
 *   GPB0–GPB6: seven push buttons (pin → GND when pressed; pull-ups enabled)
 *
 * Runs a Knight Rider scanning pattern on PORTA. Pressing a button overrides
 * the scanner and lights the matching LED. The loop reads GPIOB every 100 ms,
 * builds the output mask from the button state (inverted, since active-low),
 * ORs it with the scanner position unless a button is pressed, then writes
 * to OLATA.
 */
fun main() {
    I2CTransport(1, 0x20).use { transport ->                          // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
        val chip = Mcp23017Full(transport, 0x20)                       // construct full driver, (transport, addr=0x20) → Mcp23017Full

        chip.configurePullup(1, 0b01111111)                            // enable pull-ups, (port=1, mask) → Unit

        println("Running — press buttons GPB0–GPB6 to light corresponding LEDs")

        var position = 0
        var direction = 1

        while (true) {
            val portb = chip.readPort(1)                               // read all 8 pins, (port=1) → Int bitmask

            val buttons = portb and 0x7F
            val pressed = (~buttons) and 0x7F

            val scanner = 1 shl position

            val output = if (pressed != 0) {
                pressed or (1 shl 7)
            } else {
                scanner or (1 shl 7)
            }

            chip.writePort(0, output)                                  // write all 8 pins, (port=0, mask) → Unit

            val ledStr = (0..6).map { if ((output shr it) and 1 == 1) '*' else ' ' }.joinToString("")
            println("PORTA=0x%02X  [$ledStr]  buttons=0x%02X".format(output, buttons))

            position += direction
            if (position == 6) direction = -1
            if (position == 0) direction =  1

            Thread.sleep(100)
        }
    }
}