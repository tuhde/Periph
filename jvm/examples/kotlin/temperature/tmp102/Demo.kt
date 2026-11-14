///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.TMP102Full

// Thermostat with one-shot sampling: read the power-on state, sample at 4 Hz, switch to 8 Hz and the 13-bit mode, take one-shot readings, then run an interrupt-mode thermostat around the current temperature while the sensor is warmed with a finger. Finally the power-on defaults are restored.

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, TMP102Full.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val sensor = TMP102Full(connection)                                           // construct driver (no bus access), (connection) → TMP102Full

        println("config 0x%04X".format(sensor.readConfig()))                                      // Read CONFIGURATION, () → int
        println("limits low %.4f high %.4f".format(sensor.getTLow(), sensor.getTHigh()))          // Read T_LOW, () → double °C; Read T_HIGH, () → double °C

        // --- Continuous reading at 4 Hz for 5 s ---
        repeat(20) {
            println("%.4f °C (raw %d)".format(sensor.readTemperature(), sensor.readRaw()))        // Read temperature, () → double °C; Read raw count, () → int
            Thread.sleep(250)
        }

        // --- 8 Hz and the 13-bit extended mode ---
        sensor.setConversionRate(8.0)                                                             // Set conversion rate, (hz) → void
        sensor.setExtendedMode(true)                                                              // Set extended mode, (on) → void
        Thread.sleep(200)
        println("extended %.4f °C".format(sensor.readTemperature()))                              // Read temperature, () → double °C
        sensor.setExtendedMode(false)                                                             // Set extended mode, (on) → void
        sensor.setConversionRate(4.0)                                                             // Set conversion rate, (hz) → void

        // --- Shutdown and one-shot readings (about 10 ms each) ---
        sensor.shutdown()                                                                         // Shut down, () → void
        repeat(5) {
            println("one-shot %.4f °C".format(sensor.oneShot()))                                  // One-shot reading, () → double °C
        }
        sensor.wake()                                                                             // Wake, () → void

        // --- Interrupt-mode thermostat around the current temperature ---
        val now = sensor.readTemperature()                                                        // Read temperature, () → double °C
        sensor.setTHigh(now + 1.0)                                                                // Set T_HIGH, (celsius °C) → void
        sensor.setTLow(now - 1.0)                                                                 // Set T_LOW, (celsius °C) → void
        sensor.setFaultQueue(2)                                                                   // Set fault queue, (count) → void
        sensor.setPolarity(false)                                                                 // Set ALERT polarity, (activeHigh) → void
        sensor.setThermostatMode(true)                                                            // Set thermostat mode, (interrupt) → void
        println("warm the sensor with a finger ...")
        repeat(40) {
            if (sensor.pollInterrupt()) {                                                         // Poll the alert state, () → boolean
                println("ALERT: %.2f °C, alert_state=%s".format(sensor.readTemperature(), sensor.alertState()))// Read alert state, () → boolean
            }
            Thread.sleep(250)
        }
        sensor.onInterrupt({ c -> println("ALERT at %.2f °C".format(c)) })                        // Subscribe to ALERT, (callback) → void
        sensor.offInterrupt()                                                                     // Unsubscribe, () → void

        // --- Restore the power-on defaults ---
        sensor.setThermostatMode(false)                                                           // Set thermostat mode, (interrupt) → void
        sensor.setFaultQueue(1)                                                                   // Set fault queue, (count) → void
        sensor.setTHigh(80.0)                                                                     // Set T_HIGH, (celsius °C) → void
        sensor.setTLow(75.0)                                                                      // Set T_LOW, (celsius °C) → void
        sensor.writeConfig(0x60A0)                                                                // Write CONFIGURATION, (value) → void
        sensor.reset()                                                                            // General-call reset, () → void
    }
}
