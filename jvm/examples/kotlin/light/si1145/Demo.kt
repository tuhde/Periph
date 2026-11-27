///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1145Full
import it.uhde.periph.chips.light.Si1145Minimal

import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntConsumer

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst(Regex("^0[xX]"), "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Si1145Full(connection)                                                      // Create Si1145 driver, (connection) → Si1145Full
        sensor.init()                                                                            // Check PART_ID, HW_KEY, UV + ALS + PS1, () → Unit

        // --- Identity and calibration ---
        // PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL returns the
        // factory calibration bytes.
        val id = sensor.readId()                                                                 // Identity registers, () → Id
        System.out.printf("PART_ID 0x%02X  REV_ID 0x%02X  SEQ_ID 0x%02X%n", id.partId, id.revId, id.seqId)
        println("calibration " + sensor.readCalibration().contentToString())                     // GET_CAL, () → IntArray
        println(sensor.readResponse())                                                           // RESPONSE, () → Response

        // --- Proximity, UV index and light for 10 s ---
        // Proximity counts rise as a hand approaches the LED/sensor window.
        for (i in 0 until 10) {
            val m = sensor.read()                                                                // Forced PS + ALS, () → Reading
            System.out.printf("%2d s  prox %5d  UV %5.2f  vis %5d  ir %5d%n", i, m.proximity, m.uvIndex, m.visible, m.infrared)
            Thread.sleep(1000)
        }

        // --- LED current and photodiode ---
        // Hold a hand ~5 cm above the sensor: higher LED current gives more counts, the small
        // photodiode a fraction of the large one.
        println("Hold a hand above the sensor")
        Thread.sleep(3000)
        for (level in intArrayOf(1, 4, 8, 15)) {
            sensor.setLedCurrent(1, level)                                                       // LED drive, (led, level) → Unit
            System.out.printf("LED level %2d -> %d%n", level, sensor.readProximity())            // Forced PS, () → Int counts
        }
        sensor.setLedCurrent(1, 3)
        for (large in listOf(false, true)) {
            sensor.setPsPhotodiode(1, large)                                                     // PS photodiode, (channel, large) → Unit
            System.out.printf("%s photodiode -> %d%n", if (large) "large" else "small", sensor.readProximity())
        }
        sensor.setPsLed(1, 0x01)                                                                 // PS LED select, (channel, leds) → Unit

        // --- Gain and high range ---
        // Each gain step doubles the integration time; the high range divides the sensitivity
        // by 14.5 for direct sunlight.
        for (gain in intArrayOf(0, 2, 4)) {
            sensor.setGain(gain, gain, 0)                                                        // ADC gains, (visible, infrared, proximity) → Unit
            System.out.printf("gain %d  vis %d  ir %d%n", gain, sensor.readVisible(), sensor.readInfrared()) // Forced ALS, () → Int counts
        }
        sensor.setGain(0, 0, 0)
        sensor.setHighRange(true, true, false)                                                   // Signal range, (visible, infrared, proximity) → Unit
        System.out.printf("high range  vis %d  UV %.2f%n", sensor.readVisible(), sensor.readUvIndex()) // Forced ALS, () → int / double
        sensor.setHighRange(false, false, false)
        sensor.setAlignment(false, false, false, false, false)                                   // Result alignment, (...) → Unit
        sensor.setUvCoefficients(Si1145Minimal.UCOEF_DEFAULT)                                    // UCOEF0..3, (ucoef) → Unit

        // --- Autonomous mode with interrupt events and a proximity switch ---
        // PS + ALS every 100 ms; each finished PS1 measurement raises INT (or is polled).
        val threshold = 2000
        sensor.setChannels(true, true, true, true)                                               // CHLIST, (uv, visible, infrared, ps1) → Unit
        val events = AtomicInteger()
        sensor.enableInterrupt(Si1145Minimal.SOURCE_PS1)                                         // INT_CFG + IRQ_ENABLE, (source) → Unit
        sensor.onInterrupt(IntConsumer { events.incrementAndGet() })                             // Subscribe, (callback) → Unit
        sensor.startAutonomous(0.1)                                                              // Autonomous mode, (intervalS) → Unit
        var near = false
        for (i in 0 until 50) {
            val ps1 = sensor.readRaw().ps1                                                       // Data registers, () → Raw
            if ((ps1 > threshold) != near) {
                near = ps1 > threshold
                System.out.printf("output %s (prox %d)%n", if (near) "ON" else "OFF", ps1)
            }
            Thread.sleep(100)
        }
        sensor.pause()                                                                           // PSALS_PAUSE, () → Unit
        sensor.offInterrupt()                                                                    // Unsubscribe, () → Unit
        println("${events.get()} interrupt events")
        sensor.disableInterrupt(Si1145Minimal.SOURCE_PS1)                                        // IRQ_ENABLE clear, (source) → Unit
        sensor.pollInterrupt()                                                                   // IRQ_STATUS, () → Int sources

        // --- Auxiliary channels ---
        System.out.printf("temperature raw %d  VDD raw %d%n",
                sensor.readTemperatureRaw(), sensor.readVddRaw())                                // AUX channel, () → Int counts
        sensor.force(false, true)                                                                // ALS_FORCE without waiting, (proximity, als) → Unit
        Thread.sleep(50)
        println(sensor.readRaw())                                                                // Data registers, () → Raw

        // --- Address and reset ---
        sensor.setI2cAddress(0x60)                                                               // I2C_ADDR + BUSADDR, (address) → Unit
        sensor.reset()                                                                           // RESET + HW_KEY + defaults, () → Unit
        println("after reset " + sensor.read())
    }
}
