///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.TSL2561Full
import it.uhde.periph.chips.light.TSL2561Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, TSL2561Minimal.DEFAULT_ADDRESS).use { connection ->                         // open I²C bus, device address, (bus, address=0x39) → I2CConnection
        val sensor = TSL2561Full(connection)                                                       // construct driver, (connection) → TSL2561Full
        // powers up, CONTROL read back, TIMING ← 0x02 (1×, 402 ms), waits 450 ms

        println("id 0x%02X".format(sensor.readId()))                                               // Read the ID register, () → Int
        // bits 7:4 = 1 for the TSL2561, bits 3:0 revision
        println(sensor.readLux())                                                                  // Read illuminance, () → Double lux
        // throws SaturatedException on a saturated channel
        val (ch0, ch1) = sensor.readRaw()                                                          // Read both channels, () → Pair(ch0, ch1)
        // one block read: ch0 visible+IR, ch1 IR
        println(sensor.readLuxRaw())                                                               // Lux plus counts, () → Reading(lux, ch0, ch1)
        println(TSL2561Minimal.calculateLux(ch0, ch1, 1, 402, "T"))                                // Convert counts, (ch0, ch1, gain, integrationMs, pkg) → Double lux

        sensor.setGain(16)                                                                         // Set ADC gain, (gain 1|16) → Unit
        // 16× for dim light; saturates indoors at ~1–2 klx
        sensor.setIntegrationTime(101)                                                             // Set integration time, (ms 14|101|402) → Unit
        // the conversion in progress keeps the old setting
        println("${sensor.getGain()}x ${sensor.getIntegrationTime()} ms")                         // Current settings, () → Int, () → Int ms
        Thread.sleep(250)
        sensor.setPackage("CS")                                                                    // Select lux coefficients, (pkg "T"|"FN"|"CL"|"CS") → Unit
        // T, FN and CL share one set; CS has its own
        println(sensor.readLux())
        sensor.setPackage("T")
        println(sensor.autoRange())                                                                // Auto-range and read lux, () → Double lux
        // 16×/402 ms … 1×/13.7 ms until no saturation and ch0 > 100

        sensor.startManual()                                                                       // Start a host-timed integration, () → Unit
        // INTEG = 11, MANUAL = 1
        Thread.sleep(200)
        sensor.stopManual()                                                                        // Stop the integration, () → Unit
        // INTEG = 11, MANUAL = 0; counts are ready
        println(sensor.readRaw())
        sensor.setIntegrationTime(402)
        sensor.setGain(1)

        sensor.setThresholds(100, 20000)                                                           // Set channel-0 window, (low, high counts 0–65535) → Unit
        // word writes of 0x2–0x3 and 0x4–0x5
        println(sensor.getThresholds())                                                            // Read the window, () → Pair(low, high)
        sensor.configureInterrupt(1, 2)                                                            // Interrupt mode, (mode 0|1|2, persist 0–15) → Unit
        // level interrupt after 2 cycles outside the window
        println(sensor.pollInterrupt())                                                            // Check ch0 against the window, () → Boolean
        // does not clear the chip's interrupt
        sensor.clearInterrupt()                                                                    // Release a level interrupt, () → Unit
        // command byte 0xC0
        sensor.onInterrupt { c0 -> println("interrupt $c0") }                                     // Subscribe, (intPin=null, callback(ch0)) → Unit
        // falling edge on INT, or poll once per integration
        Thread.sleep(10000)
        sensor.offInterrupt()                                                                      // Unsubscribe, () → Unit
        sensor.configureInterrupt(0, 0)

        println(sensor.isPowered())                                                                // Power state, () → Boolean
        // CONTROL reads 0x03 when powered
        sensor.powerDown()                                                                         // Power down, () → Unit
        // CONTROL = 0x00; registers keep their values
        sensor.powerUp()                                                                           // Power up, () → Unit
        // CONTROL = 0x03; integration restarts
    }
}
