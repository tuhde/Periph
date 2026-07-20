///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.io_expander.Pcf8574Full;

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
public class Demo {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x20)) {             // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
            var chip = new Pcf8574Full(transport);                     // construct full driver, (transport) → Pcf8574Full

            // --- Configure P0–P3 as outputs (LED control), P4–P7 as inputs (buttons) ---
            // Writing 0 to bits 0–3 drives the LED pins low (LEDs off initially).
            // Bits 4–7 stay at 1 (input mode) to sense button presses.
            chip.writePort(0xF0);                                      // write all 8 pins, (mask=0xF0) → void

            System.out.println("Running — press buttons P4–P7 to mirror to LEDs P0–P3");

            // --- Main loop: read buttons → mirror to LEDs every 200 ms ---
            // The port byte encodes both the LED drive state (bits 0–3) and the actual
            // button logic level (bits 4–7). Reading back a pin written 1 returns the
            // true external level, making quasi-bidirectional mode visible in the output.
            while (true) {
                int port = chip.readPort();                             // read all 8 pins, () → int bitmask

                int buttons = (port >> 4) & 0x0F;   // extract P4–P7 (pressed = 0, released = 1)
                int leds    = (~buttons) & 0x0F;     // invert: button pressed → LED on (drive low = 0)
                chip.writePort(0xF0 | leds);                           // write all 8 pins, (mask) → void

                String btnStr = String.format("%c%c%c%c",
                    (buttons >> 3 & 1) == 0 ? 'X' : '.', (buttons >> 2 & 1) == 0 ? 'X' : '.',
                    (buttons >> 1 & 1) == 0 ? 'X' : '.', (buttons      & 1) == 0 ? 'X' : '.');
                String ledStr = String.format("%c%c%c%c",
                    (leds >> 3 & 1) == 1 ? '*' : ' ', (leds >> 2 & 1) == 1 ? '*' : ' ',
                    (leds >> 1 & 1) == 1 ? '*' : ' ', (leds      & 1) == 1 ? '*' : ' ');
                System.out.printf("port=0x%02X  buttons=[%s]  LEDs=[%s]%n", port, btnStr, ledStr);

                Thread.sleep(200);
            }
        }
    }
}
