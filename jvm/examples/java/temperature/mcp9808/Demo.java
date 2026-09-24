///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.temperature.MCP9808Full;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Industrial freezer monitor: the healthy range is -25 °C to -15 °C, and
 * -5 °C means the door has been left open too long. The Alert output fires
 * in interrupt mode each time the temperature leaves or re-enters the
 * window; each event is reported with the boundary that tripped.
 */
public class Demo {
    static final int MAX_ALERTS = 10;

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, MCP9808Full.DEFAULT_ADDRESS)) {      // open I²C bus, device address, (bus, address=0x18) → I2CConnection
            var sensor = new MCP9808Full(connection);                                     // construct driver and check identity, (connection) → MCP9808Full

            // --- Trade resolution for faster sampling ---
            // 0.25 °C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
            // so a door opening shows up in the next reading almost immediately.
            sensor.setResolution(0.25);                                                   // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → void

            // --- Program the healthy window and the door-open threshold ---
            // TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
            // 3 °C of hysteresis stops the Alert chattering while the compressor cycles.
            sensor.setLowerLimit(-25.0);                                                  // Set TLOWER, (celsius °C) → void
            sensor.setUpperLimit(-15.0);                                                  // Set TUPPER, (celsius °C) → void
            sensor.setCriticalLimit(-5.0);                                                // Set TCRIT, (celsius °C) → void
            sensor.setHysteresis(3.0);                                                    // Set hysteresis, (celsius 0|1.5|3.0|6.0) → void

            // --- Route every boundary to the Alert pin as a latched interrupt ---
            // Interrupt mode latches each crossing until clearInterrupt(), so a short
            // excursion is never missed between two reads.
            sensor.configureAlert(MCP9808Full.AlertMode.ALL, MCP9808Full.AlertOutput.INTERRUPT, MCP9808Full.AlertPolarity.ACTIVE_LOW);  // Configure Alert, (mode=ALL, output=COMPARATOR, polarity=ACTIVE_LOW) → void
            sensor.enableAlert();                                                         // Enable Alert output, () → void

            // --- Report which boundary tripped, then re-arm ---
            // The status mask is a live read of TA's boundary bits; an empty mask
            // means the temperature has come back inside the healthy window.
            var events = new LinkedBlockingQueue<Integer>();
            sensor.onInterrupt(events::add);                                              // Subscribe to Alert, (callback) → void
            System.out.printf("monitoring, %.2f °C now%n", sensor.readTemperature());     // Read ambient temperature, () → double °C
            for (int alerts = 0; alerts < MAX_ALERTS; alerts++) {
                int status = events.take();
                double t = sensor.readTemperature();                                      // Read ambient temperature, () → double °C
                if ((status & MCP9808Full.SOURCE_CRITICAL) != 0) System.out.printf("%.2f °C  CRITICAL - door open?%n", t);
                else if ((status & MCP9808Full.SOURCE_UPPER) != 0) System.out.printf("%.2f °C  too warm%n", t);
                else if ((status & MCP9808Full.SOURCE_LOWER) != 0) System.out.printf("%.2f °C  too cold%n", t);
                else System.out.printf("%.2f °C  back in range%n", t);
                sensor.clearInterrupt();                                                  // Clear interrupt-mode Alert, () → void
            }

            // --- Shut down cleanly after the demo run ---
            sensor.disableAlert();                                                        // Disable Alert output, () → void
            sensor.offInterrupt();                                                        // Unsubscribe, () → void
        }
    }
}
