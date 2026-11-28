///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.TSL2561Full
import it.uhde.periph.chips.light.TSL2561Minimal

/*
 * Light meter with alarm: prints the ID, takes an auto-ranged lux reading every
 * 2 s for 30 s with gain, integration time and both channel counts, then sets a
 * ±50 % channel-0 window with persistence 2, reports pollInterrupt() while you
 * cover the sensor, releases the interrupt and powers the chip down.
 */
fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, TSL2561Minimal.DEFAULT_ADDRESS).use { connection ->                         // open I²C bus, device address, (bus, address=0x39) → I2CConnection
        val sensor = TSL2561Full(connection)                                                       // construct driver, (connection) → TSL2561Full
        println("ID 0x%02X".format(sensor.readId()))                                               // Read the ID register, () → Int

        /* Light meter: auto-range picks the most sensitive setting that does not saturate. */
        repeat(15) {
            val lux = sensor.autoRange()                                                           // Auto-range and read lux, () → Double lux
            val (ch0, ch1) = sensor.readRaw()                                                      // Read both channels, () → Pair(ch0, ch1)
            println("%9.1f lx  gain %2dx  %3d ms  ch0 %5d  ch1 %5d".format(
                lux, sensor.getGain(), sensor.getIntegrationTime(), ch0, ch1))                     // Current settings, () → Int
            Thread.sleep(2000)
        }

        /* Alarm: a window of ±50 % around the current channel-0 count. */
        val ch0 = sensor.readRaw().first
        sensor.setThresholds(ch0 / 2, minOf(65535, ch0 * 3 / 2))                                   // Set channel-0 window, (low, high) → Unit
        sensor.configureInterrupt(1, 2)                                                            // Level interrupt, 2 cycles outside, (mode, persist) → Unit
        println("window ${sensor.getThresholds()} - cover the sensor now")                         // Read the window, () → Pair(low, high)
        repeat(10) {
            println("outside window: ${sensor.pollInterrupt()}")                                   // Check ch0 against the window, () → Boolean
            Thread.sleep(1000)
        }

        sensor.clearInterrupt()                                                                    // Release the level interrupt, () → Unit
        sensor.configureInterrupt(0, 0)
        sensor.powerDown()                                                                         // Power down, () → Unit
        println("powered: ${sensor.isPowered()}")                                                  // Power state, () → Boolean
    }
}
