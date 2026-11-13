///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.MAX44009Full
import kotlin.math.log10

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, MAX44009Full.DEFAULT_ADDRESS).use { connection ->                           // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
        val sensor = MAX44009Full(connection)                                                      // construct driver, (connection) → MAX44009Full
        println(sensor.lux())                                                                      // Read illuminance, () → double lux
        // 2^E × M × 0.045; 188006.4 on overrange
        val raw = sensor.readRaw()                                                                 // Read exponent and mantissa, () → Raw
        // one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
        println("E=${raw.exponent} M=${raw.mantissa} overrange=${raw.overrange}")
        println(sensor.isOverrange())                                                              // Query the last reading, () → boolean
        // true if the last exponent field was 15
        println(sensor.resolution())                                                               // Lux per LSB, () → double lux
        // 2^E × 0.045 for the last reading

        sensor.setContinuous(true)                                                                 // Select measurement mode, (continuous boolean) → void
        // true: a new measurement as soon as one finishes
        sensor.setManual(100.0, false)                                                             // Select manual range, (integrationMs, divideBy8) → void
        // nearest of 800/400/200/100/50/25/12.5/6.25 ms
        println(sensor.getConfig().integrationMs)                                                  // Read CONFIG, () → Config
        sensor.setAutomatic()                                                                      // Select automatic range, () → void
        // the chip chooses the current divider and integration time
        sensor.setContinuous(false)                                                                // Back to one measurement per 800 ms, (continuous boolean) → void

        sensor.setThresholds(50.0, 500.0)                                                          // Set the interrupt window, (lowLux, highLux) → void
        // 4 mantissa MSBs: about 1/16 to 1/8 of the value
        println(sensor.getThresholds())                                                            // Read the window, () → {lowLux, highLux}
        sensor.setThresholdDelay(2.0)                                                              // Set the threshold delay, (seconds) → void
        // 0–25.5 s in 0.1 s steps
        println(sensor.getThresholdDelay())                                                        // Read the delay, () → double s

        sensor.enableInterrupt()                                                                   // Enable the interrupt, () → void
        // INT_ENABLE = 1
        sensor.onInterrupt { status -> println("interrupt $status") }                              // Subscribe, (callback(status)) → void
        // falling edge on INT, or a 100 ms polling thread without a pin
        Thread.sleep(10000)
        println(sensor.pollInterrupt())                                                            // Read and clear INT_STATUS, () → int
        // bit 0 = SOURCE_THRESHOLD
        sensor.offInterrupt()                                                                      // Unsubscribe, () → void
        sensor.disableInterrupt()                                                                  // Disable the interrupt, () → void
        // INT_ENABLE = 0; also clears a pending status
    }
}
