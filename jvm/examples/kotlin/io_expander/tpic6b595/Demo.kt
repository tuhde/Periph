///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
import it.uhde.periph.connection.SiPoConnection
import it.uhde.periph.chips.io_expander.Tpic6b595Full

fun main() {
    val numDevices = 2
    val numOutputs = numDevices * 8
    val blankEvery = 3
    val blankMs = 500L

    SiPoConnection.hardware(0, 0, 17, 16, 15).use { connection ->     // open SiPo connection, (bus, device, rckLine, srclrLine, gLine) → SiPoConnection
        val chip = Tpic6b595Full(connection, numDevices)              // construct full driver, (connection, numDevices=2) → Tpic6b595Full
                                                                       // two cascaded devices — 16 outputs total; outputs start OFF

        var position = 0
        var direction = 1
        var sweepCount = 0

        while (true) {
            // --- Walk a single lit LED across all 16 outputs and back ---
            // Use writeAll() each step so both cascaded devices latch together —
            // there is no way to update just one downstream device without re-sending
            // the whole chain's data.
            val bytes = IntArray(numDevices)
            val port = position / 8
            val bit = position % 8
            bytes[port] = 1 shl bit
            chip.writeAll(bytes)                                        // write all device bytes, (values=[0x01, 0x80]) → Unit

            println("position=$position  bytes=[0x%02X, 0x%02X]".format(bytes[0], bytes[1]))

            // --- Periodically blank every output via G, then resume ---
            // setOutputEnable(false) drives G HIGH, forcing every DMOS off without
            // touching the shadow register — the LEDs simply resume exactly where they
            // left off when G is re-enabled.
            sweepCount++
            if (sweepCount % blankEvery == 0) {
                chip.setOutputEnable(false)                             // force every output off via G, (enabled=false) → Unit
                                                                       // the chase pattern's shadow state is preserved
                println("  blanked via G for $blankMs ms")
                Thread.sleep(blankMs)
                chip.setOutputEnable(true)                              // re-enable outputs, (enabled=true) → Unit
                                                                       // LEDs resume from the previously-latched state
            }

            // Bounce the chase position at both ends of the strip
            position += direction
            if (position >= numOutputs - 1 || position <= 0) {
                direction = -direction
                Thread.sleep(100)
            } else {
                Thread.sleep(80)
            }
        }
    }
}
