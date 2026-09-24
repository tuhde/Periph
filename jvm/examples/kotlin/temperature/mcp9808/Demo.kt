///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.MCP9808Full
import it.uhde.periph.chips.temperature.MCP9808Minimal
import java.util.concurrent.LinkedBlockingQueue

// Industrial freezer monitor: the healthy range is -25 °C to -15 °C, and
// -5 °C means the door has been left open too long. The Alert output fires
// in interrupt mode each time the temperature leaves or re-enters the
// window; each event is reported with the boundary that tripped.

const val MAX_ALERTS = 10

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, MCP9808Minimal.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x18) → I2CConnection
        val sensor = MCP9808Full(connection)                                              // construct driver and check identity, (connection) → MCP9808Full

        // --- Trade resolution for faster sampling ---
        // 0.25 °C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
        // so a door opening shows up in the next reading almost immediately.
        sensor.setResolution(0.25)                                                        // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → Unit

        // --- Program the healthy window and the door-open threshold ---
        // TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
        // 3 °C of hysteresis stops the Alert chattering while the compressor cycles.
        sensor.setLowerLimit(-25.0)                                                       // Set TLOWER, (celsius °C) → Unit
        sensor.setUpperLimit(-15.0)                                                       // Set TUPPER, (celsius °C) → Unit
        sensor.setCriticalLimit(-5.0)                                                     // Set TCRIT, (celsius °C) → Unit
        sensor.setHysteresis(3.0)                                                         // Set hysteresis, (celsius 0|1.5|3.0|6.0) → Unit

        // --- Route every boundary to the Alert pin as a latched interrupt ---
        // Interrupt mode latches each crossing until clearInterrupt(), so a short
        // excursion is never missed between two reads.
        sensor.configureAlert(MCP9808Full.AlertMode.ALL, MCP9808Full.AlertOutput.INTERRUPT, MCP9808Full.AlertPolarity.ACTIVE_LOW)  // Configure Alert, (mode=ALL, output=COMPARATOR, polarity=ACTIVE_LOW) → Unit
        sensor.enableAlert()                                                              // Enable Alert output, () → Unit

        // --- Report which boundary tripped, then re-arm ---
        // The status mask is a live read of TA's boundary bits; an empty mask
        // means the temperature has come back inside the healthy window.
        val events = LinkedBlockingQueue<Int>()
        sensor.onInterrupt({ events.add(it) })                                            // Subscribe to Alert, (callback) → Unit
        println("monitoring, %.2f °C now".format(sensor.readTemperature()))               // Read ambient temperature, () → double °C
        repeat(MAX_ALERTS) {
            val status = events.take()
            val t = sensor.readTemperature()                                              // Read ambient temperature, () → double °C
            val label = when {
                status and MCP9808Full.SOURCE_CRITICAL != 0 -> "CRITICAL - door open?"
                status and MCP9808Full.SOURCE_UPPER != 0 -> "too warm"
                status and MCP9808Full.SOURCE_LOWER != 0 -> "too cold"
                else -> "back in range"
            }
            println("%.2f °C  %s".format(t, label))
            sensor.clearInterrupt()                                                       // Clear interrupt-mode Alert, () → Unit
        }

        // --- Shut down cleanly after the demo run ---
        sensor.disableAlert()                                                             // Disable Alert output, () → Unit
        sensor.offInterrupt()                                                             // Unsubscribe, () → Unit
    }
}
