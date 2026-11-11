///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.PCA6408AFull
import java.util.function.IntConsumer

/**
 * PCA6408A demo — LED scanner with interrupt-driven button input.
 *
 * Wire four active-low LEDs (anode to VDD(P) through 220 Ω) to P0–P3 and four push buttons
 * (to GND, with external pull-ups: the chip has none) to P4–P7. A button interrupt prints the
 * changed mask and the port state and holds the matching LED (P4→P0, …) for one second.
 */
def connection = new I2CConnection(1, 0x20)           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
try {
    def chip = new PCA6408AFull(connection, 0x20)                         // construct driver, (connection, addr=0x20) → PCA6408AFull

    // --- Configure LEDs and buttons ---
    // P0–P3 are active-low LED outputs (0 lights the LED); P4–P7 stay inputs and rely on
    // external pull-ups (the chip has none), so a pressed button (to GND) reads 0.
    chip.writePort(0, 0xFF)                                               // turn all LEDs off, (port=0, mask=0xFF) → void
    chip.setDirection(0xF0)                                               // P0–P3 outputs, P4–P7 inputs, (mask) → void

    // --- Subscribe to input changes ---
    // Any button edge raises INT (or the 5 ms poll sees it); the callback prints the changed
    // mask and the port state and holds the LED matching the pressed button for one second.
    long[] holdUntil = [0L] as long[]
    int[] holdMask = [0x0F] as int[]
    chip.onInterrupt({ int changed ->                                     // subscribe to INT, (callback[, intPin]) → void
        int port = chip.readPort(0)
        printf("button changed=0x%02X port=0x%02X%n", changed, port)
        holdMask[0] = ~((changed >> 4) & 0x0F) & 0x0F
        holdUntil[0] = System.currentTimeMillis() + 1000
    } as IntConsumer)

    // --- Scanner loop ---
    for (int step = 0; step < 40; step++) {
        int leds = System.currentTimeMillis() < holdUntil[0] ? holdMask[0] : (~(1 << (step % 4)) & 0x0F)
        chip.writePort(0, 0xF0 | leds)                                    // write LED outputs, (port=0, mask) → void
        int port = chip.readPort(0)                                       // read all 8 pins, (port=0) → int bitmask
        println("  pos=%d  port=0x%02X".format(step % 4, port))
        Thread.sleep(100)
    }

    // --- Shut down ---
    chip.offInterrupt()                                                   // unsubscribe, () → void
    chip.writePort(0, 0xFF)                                               // turn all LEDs off, (port=0, mask=0xFF) → void
} finally {
    connection.close()
}
