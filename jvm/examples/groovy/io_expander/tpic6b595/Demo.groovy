///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
import it.uhde.periph.connection.SiPoConnection
import it.uhde.periph.chips.io_expander.Tpic6b595Full

final int NUM_DEVICES = 2
final int NUM_OUTPUTS = NUM_DEVICES * 8
final int BLANK_EVERY = 3
final long BLANK_MS = 500

def connection = SiPoConnection.hardware(0, 0, 17, 16, 15)      // open SiPo connection, (bus, device, rckLine, srclrLine, gLine) → SiPoConnection
try {
    def chip = new Tpic6b595Full(connection, NUM_DEVICES)        // construct full driver, (connection, numDevices=2) → Tpic6b595Full
                                                                   // two cascaded devices — 16 outputs total; outputs start OFF

    int position = 0
    int direction = 1
    int sweepCount = 0

    while (true) {
        // --- Walk a single lit LED across all 16 outputs and back ---
        // Use writeAll() each step so both cascaded devices latch together —
        // there is no way to update just one downstream device without re-sending
        // the whole chain's data.
        int[] bytes = new int[NUM_DEVICES]
        int port = position / 8
        int bit = position % 8
        bytes[port] = 1 << bit
        chip.writeAll(bytes)                                       // write all device bytes, (values=[0x01, 0x80]) → void

        println(String.format("position=%2d  bytes=[0x%02X, 0x%02X]", position, bytes[0], bytes[1]))

        // --- Periodically blank every output via G, then resume ---
        // setOutputEnable(false) drives G HIGH, forcing every DMOS off without
        // touching the shadow register — the LEDs simply resume exactly where they
        // left off when G is re-enabled.
        sweepCount++
        if (sweepCount % BLANK_EVERY == 0) {
            chip.setOutputEnable(false)                             // force every output off via G, (enabled=false) → void
                                                                   // the chase pattern's shadow state is preserved
            println("  blanked via G for ${BLANK_MS} ms")
            Thread.sleep(BLANK_MS)
            chip.setOutputEnable(true)                              // re-enable outputs, (enabled=true) → void
                                                                   // LEDs resume from the previously-latched state
        }

        // Bounce the chase position at both ends of the strip
        position += direction
        if (position >= NUM_OUTPUTS - 1 || position <= 0) {
            direction = -direction
            Thread.sleep(100)
        } else {
            Thread.sleep(80)
        }
    }
} finally {
    connection.close()
}
