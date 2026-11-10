///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.Mcp23008Full;

/**
 * MCP23008 demo — LED scanner with interrupt-driven button input.
 *
 * Wire four LEDs (active-high, through 220 Ω) to GP0–GP3 and four push buttons (to GND,
 * pull-ups enabled) to GP4–GP7. A button interrupt prints INTF/INTCAP and holds the
 * matching LED (GP4→GP0, …) for one second.
 */
public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x20)) {           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
            var chip = new Mcp23008Full(connection, 0x20);                        // construct driver, (connection, addr=0x20) → Mcp23008Full

            // --- Configure LEDs and buttons ---
            // GP0–GP3 are LED outputs; GP4–GP7 stay inputs with the 100 kΩ pull-ups on,
            // so a pressed button (to GND) reads 0.
            for (int n = 0; n < 4; n++) {
                chip.pin(n).setOutput();                                          // set LED pin as output, () → void
            }
            chip.configurePullup(0xF0);                                           // pull-ups on GP4–GP7, (mask) → void

            // --- Arm interrupt-on-change on the buttons ---
            // Any button edge raises INT (or the 5 ms poll sees it); the callback prints INTF and
            // the INTCAP snapshot and holds the LED matching the pressed button for one second.
            long[] holdUntil = {0};
            int[] holdMask = {0};
            chip.enablePinInterrupt(0xF0);                                        // arm GP4–GP7, (mask) → void
            chip.onInterrupt(status -> {                                          // subscribe to INT, (callback[, intPin, compareToDefault]) → void
                        int captured;
            try { captured = chip.readCapture(); } catch (java.io.IOException e) { return; }
            System.out.printf("button INTF=0x%02X INTCAP=0x%02X%n", status, captured);
            holdMask[0] = (status >> 4) & 0x0F;
            holdUntil[0] = System.currentTimeMillis() + 1000;
        });

            // --- Scanner loop ---
            for (int step = 0; step < 40; step++) {
                int leds = System.currentTimeMillis() < holdUntil[0] ? holdMask[0] : (1 << (step % 4));
                chip.writePort(0, leds);                                          // write LED outputs, (port=0, mask) → void
                int port = chip.readPort(0);                                      // read all 8 pins, (port=0) → int bitmask
                System.out.printf("  pos=%d  port=0x%02X%n", step % 4, port);
                Thread.sleep(100);
            }

            // --- Shut down ---
            chip.offInterrupt();                                                  // unsubscribe, () → void
            chip.writePort(0, 0x00);                                              // clear all outputs, (port=0, mask=0x00) → void
        }
    }
}
