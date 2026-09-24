///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.MCP9808Full
import java.util.function.IntConsumer
import java.util.concurrent.LinkedBlockingQueue

// Industrial freezer monitor: the healthy range is -25 °C to -15 °C, and
// -5 °C means the door has been left open too long. The Alert output fires
// in interrupt mode each time the temperature leaves or re-enters the
// window; each event is reported with the boundary that tripped.

final int MAX_ALERTS = 10

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, MCP9808Full.DEFAULT_ADDRESS)                      // open I²C bus, device address, (bus, address=0x18) → I2CConnection
try {
    def sensor = new MCP9808Full(connection)                                              // construct driver and check identity, (connection) → MCP9808Full

    // --- Trade resolution for faster sampling ---
    // 0.25 °C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
    // so a door opening shows up in the next reading almost immediately.
    sensor.setResolution(0.25d)                                                           // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → void

    // --- Program the healthy window and the door-open threshold ---
    // TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
    // 3 °C of hysteresis stops the Alert chattering while the compressor cycles.
    sensor.setLowerLimit(-25.0d)                                                          // Set TLOWER, (celsius °C) → void
    sensor.setUpperLimit(-15.0d)                                                          // Set TUPPER, (celsius °C) → void
    sensor.setCriticalLimit(-5.0d)                                                        // Set TCRIT, (celsius °C) → void
    sensor.setHysteresis(3.0d)                                                            // Set hysteresis, (celsius 0|1.5|3.0|6.0) → void

    // --- Route every boundary to the Alert pin as a latched interrupt ---
    // Interrupt mode latches each crossing until clearInterrupt(), so a short
    // excursion is never missed between two reads.
    sensor.configureAlert(MCP9808Full.AlertMode.ALL, MCP9808Full.AlertOutput.INTERRUPT, MCP9808Full.AlertPolarity.ACTIVE_LOW)  // Configure Alert, (mode=ALL, output=COMPARATOR, polarity=ACTIVE_LOW) → void
    sensor.enableAlert()                                                                  // Enable Alert output, () → void

    // --- Report which boundary tripped, then re-arm ---
    // The status mask is a live read of TA's boundary bits; an empty mask
    // means the temperature has come back inside the healthy window.
    def events = new LinkedBlockingQueue<Integer>()
    sensor.onInterrupt({ int st -> events.add(st) } as IntConsumer)                       // Subscribe to Alert, (callback) → void
    printf("monitoring, %.2f °C now%n", sensor.readTemperature())                         // Read ambient temperature, () → double °C
    MAX_ALERTS.times {
        int status = events.take()
        double t = sensor.readTemperature()                                               // Read ambient temperature, () → double °C
        String label = (status & MCP9808Full.SOURCE_CRITICAL) ? "CRITICAL - door open?"
                : (status & MCP9808Full.SOURCE_UPPER) ? "too warm"
                : (status & MCP9808Full.SOURCE_LOWER) ? "too cold"
                : "back in range"
        printf("%.2f °C  %s%n", t, label)
        sensor.clearInterrupt()                                                           // Clear interrupt-mode Alert, () → void
    }

    // --- Shut down cleanly after the demo run ---
    sensor.disableAlert()                                                                 // Disable Alert output, () → void
    sensor.offInterrupt()                                                                 // Unsubscribe, () → void
} finally {
    connection.close()
}
