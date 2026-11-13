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
        val backlight = { lx: Double -> 10 + 90 * (log10(minOf(10000.0, maxOf(0.1, lx))) + 1) / 5 }

        println("manual ${sensor.getConfig().manual}")                                             // Read CONFIG, () → Config
        val lux = sensor.lux()                                                                     // Read illuminance, () → double lux
        println("lux $lux, resolution ${sensor.resolution()} lx/LSB")                              // Lux per LSB, () → double lux

        // Arm a window of +-30 % around the current illuminance; the callback only records the event.
        val pending = java.util.concurrent.atomic.AtomicInteger()
        sensor.setThresholdDelay(2.0)                                                              // Set the threshold delay, (seconds) → void
        sensor.setThresholds(lux * 0.7, lux * 1.3)                                                 // Set the interrupt window, (lowLux, highLux) → void
        sensor.enableInterrupt()                                                                   // Enable the interrupt, () → void
        sensor.onInterrupt { status -> pending.set(status) }                                       // Subscribe, (callback(status)) → void

        // Run for 60 s: react to interrupts, print the raw reading once a second.
        for (s in 0 until 60) {
            if (pending.getAndSet(0) != 0) {
                val now = sensor.lux()                                                             // Read illuminance, () → double lux
                println("lux $now -> backlight ${backlight(now).toInt()} %")
                sensor.setThresholds(now * 0.7, now * 1.3)                                         // Set the interrupt window, (lowLux, highLux) → void
            }
            val raw = sensor.readRaw()                                                             // Read exponent and mantissa, () → Raw
            println("E=${raw.exponent} M=${raw.mantissa} overrange=${sensor.isOverrange()}")
            Thread.sleep(1000)
        }
        sensor.disableInterrupt()                                                                  // Disable the interrupt, () → void
        sensor.offInterrupt()                                                                      // Unsubscribe, () → void
    }
}
