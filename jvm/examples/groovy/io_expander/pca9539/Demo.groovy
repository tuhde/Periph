///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA9539Full
import java.util.function.IntConsumer

/**
 * PCA9539 demo — LED scanner with interrupt-driven button input.
 *
 * Wire eight active-low LEDs (anode to VCC through 220 Ω) to P00–P07 and eight push buttons
 * (to GND, with external pull-ups: the chip has none) to P10–P17. A button interrupt prints the
 * changed mask and the pin state and holds the matching LED (P10→P00, …) for one second.
 */
def connection = new I2CConnection(1, 0x74)           // open I²C bus 1, device 0x74, (bus, address) → I2CConnection
try {
    def chip = new PCA9539Full(connection, 0x74)                         // construct driver, (connection, addr=0x74) → PCA9539Full

    // --- Configure LEDs and buttons ---
    // Port 0 holds the active-low LED outputs (0 lights the LED); port 1 stays input and relies on
    // external pull-ups (the chip has none), so a pressed button (to GND) reads 0. Polarity
    // inversion makes a pressed button read as 1.
    chip.writeAll(0xFFFF)                                               // turn all LEDs off, (value=0xFFFF) → void
    chip.setDirection(0xFF00)                                               // port 0 outputs, port 1 inputs, (mask) → void
    chip.configurePolarity(0xFF00)                                          // invert port 1 inputs, (mask) → void

    // --- Subscribe to input changes ---
    // Any button edge raises INT (or the 5 ms poll sees it); the callback prints the 16-bit changed
    // mask and the pin state and holds the LED matching the pressed button for one second.
    long[] holdUntil = [0L] as long[]
    int[] holdMask = [0xFF] as int[]
    chip.onInterrupt({ int changed ->                                     // subscribe to INT, (callback[, intPin]) → void
        int all = chip.readAll()
        printf("button changed=0x%04X all=0x%04X%n", changed, all)
        holdMask[0] = ~((changed >> 8) & 0xFF) & 0xFF
        holdUntil[0] = System.currentTimeMillis() + 1000
    } as IntConsumer)

    // --- Scanner loop ---
    for (int step = 0; step < 80; step++) {
        int leds = System.currentTimeMillis() < holdUntil[0] ? holdMask[0] : (~(1 << (step % 8)) & 0xFF)
        chip.writeAll(0xFF00 | leds)                                    // write LED outputs, (value) → void
        int all = chip.readAll()                                       // read all 16 pins, () → int
        println("  pos=%d  all=0x%04X".format(step % 8, port))
        Thread.sleep(100)
    }

    // --- Shut down ---
    chip.offInterrupt()                                                   // unsubscribe, () → void
    chip.writeAll(0xFFFF)                                               // turn all LEDs off, (value=0xFFFF) → void
} finally {
    connection.close()
}
