///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.temperature.TMP102Full

// Exercises every method in the TMP102 public API; the power-on limits are restored at the end.

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, TMP102Full.DEFAULT_ADDRESS).use { connection ->                // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val sensor = TMP102Full(connection)                                           // construct driver (no bus access), (connection) → TMP102Full

        val t = sensor.readTemperature()                                                          // Read temperature, () → double °C
                                                                                                  // decodes TEMPERATURE, 0.0625 °C two's complement
        println("temperature %.4f °C".format(t))
        println("raw %d".format(sensor.readRaw()))                                                // Read raw count, () → int
                                                                                                  // signed 12/13-bit count, 0.0625 °C per LSB
        println("config 0x%04X".format(sensor.readConfig()))                                      // Read CONFIGURATION, () → int
                                                                                                  // reset value 0x60A0
        sensor.writeConfig(sensor.readConfig())                                                   // Write CONFIGURATION, (value) → void
                                                                                                  // read-only bits are masked out

        sensor.setConversionRate(8.0)                                                             // Set conversion rate, (hz 0.25|1|4|8) → void
                                                                                                  // CR1:CR0 bits
        sensor.setExtendedMode(true)                                                              // Set extended mode, (on) → void
                                                                                                  // EM bit, 13-bit readings up to +255.9375 °C
        Thread.sleep(200)
        println("extended %.4f °C".format(sensor.readTemperature()))                              // Read temperature, () → double °C
        sensor.setExtendedMode(false)                                                             // Set extended mode, (on) → void
                                                                                                  // back to 12 bit

        sensor.setTHigh(30.0)                                                                     // Set T_HIGH, (celsius °C) → void
                                                                                                  // rounded to 0.0625 °C
        sensor.setTLow(10.0)                                                                      // Set T_LOW, (celsius °C) → void
                                                                                                  // rounded to 0.0625 °C
        println("high %.4f °C".format(sensor.getTHigh()))                                         // Read T_HIGH, () → double °C
                                                                                                  // same format as TEMPERATURE
        println("low %.4f °C".format(sensor.getTLow()))                                           // Read T_LOW, () → double °C
                                                                                                  // same format as TEMPERATURE

        sensor.setFaultQueue(2)                                                                   // Set fault queue, (count 1|2|4|6) → void
                                                                                                  // consecutive faults before ALERT
        sensor.setPolarity(false)                                                                 // Set ALERT polarity, (activeHigh) → void
                                                                                                  // POL bit, false = active low
        sensor.setThermostatMode(false)                                                           // Set thermostat mode, (interrupt) → void
                                                                                                  // TM bit, false = comparator
        println("alert %s".format(sensor.alertState()))                                           // Read alert state, () → boolean
                                                                                                  // AL bit corrected for POL

        sensor.shutdown()                                                                         // Shut down, () → void
                                                                                                  // SD bit, stops after the current conversion
        println("one-shot %.4f °C".format(sensor.oneShot()))                                      // One-shot reading, () → double °C
                                                                                                  // OS=1, polls until done, restores the mode
        sensor.wake()                                                                             // Wake, () → void
                                                                                                  // resume continuous conversion

        println("polling %s".format(sensor.pollInterrupt()))                                      // Poll the alert state, () → boolean
                                                                                                  // reads CONFIGURATION and TEMPERATURE
        sensor.onInterrupt({ c -> println("ALERT at %.2f °C".format(c)) })                        // Subscribe to ALERT, (callback) → void
                                                                                                  // callback receives the temperature in °C
        Thread.sleep(3000)
        sensor.offInterrupt()                                                                     // Unsubscribe, () → void
                                                                                                  // stop delivery

        sensor.setConversionRate(4.0)                                                             // Set conversion rate, (hz) → void
                                                                                                  // back to the power-on default
        sensor.setTHigh(80.0)                                                                     // Set T_HIGH, (celsius °C) → void
        sensor.setTLow(75.0)                                                                      // Set T_LOW, (celsius °C) → void
        sensor.reset()                                                                            // General-call reset, () → void
                                                                                                  // resets every general-call device on the bus
    }
}
