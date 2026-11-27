///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1145Full
import it.uhde.periph.chips.light.Si1145Minimal

import java.util.function.IntConsumer

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst(Regex("^0[xX]"), "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Si1145Full(connection)                                                      // Create Si1145 driver, (connection) → Si1145Full
        // Fixed address 0x60 (older silicon: 0x5A); the constructor does no bus traffic.
        sensor.init()                                                                            // Check PART_ID, HW_KEY, UV + ALS + PS1, () → Unit
        // Waits 25 ms, verifies PART_ID 0x45/0x46/0x47, writes HW_KEY 0x17, UCOEF, CHLIST 0xB1, LED1 level 3.

        println(sensor.readId())                                                                 // Identity registers, () → Id {partId, revId, seqId}
        // PART_ID 0x45 for the Si1145; SEQ_ID 0x01 parts swap the MEAS_RATE bytes.
        println(sensor.readResponse())                                                           // RESPONSE decoded, () → Response {counter, error}
        // 4-bit command counter and the error code (0 = none, 0x8x = ADC overflow / invalid setting).

        println(sensor.read())                                                                   // Forced PS + ALS, () → Reading {proximity, uvIndex, visible, infrared}
        // PSALS_FORCE, waits for ALS_INT and PS1_INT; raw counts and the UV index (AUX_DATA / 100).
        println(sensor.readProximity(1))                                                         // Forced PS, (channel=1) → Int counts
        // PS1 only; PS2/PS3 exist on the Si1146/47.
        println(sensor.readUvIndex())                                                            // Forced ALS, () → Double UV index
        // UV index with the default UV coefficients 7B 6B 01 00.
        println(sensor.readVisible())                                                            // Forced ALS, () → Int counts
        // Raw visible ambient light.
        println(sensor.readInfrared())                                                           // Forced ALS, () → Int counts
        // Raw infrared ambient light.
        println(sensor.readRaw())                                                                // Data registers, () → Raw {visible, infrared, ps1, ps2, ps3, aux}
        // No new measurement; the latest values of 0x22–0x2D.
        println(sensor.readTemperatureRaw())                                                     // AUX temperature, () → Int counts
        // Relative die temperature; CHLIST is switched to EN_AUX and restored.
        println(sensor.readVddRaw())                                                             // AUX VDD, () → Int counts
        // Supply voltage on the auxiliary channel.

        sensor.setChannels(true, true, true, true, false, false, false)                          // CHLIST, (uv, visible, infrared, ps1, ps2, ps3, aux) → Unit
        // UV and AUX share AUX_DATA, so only one of them can be enabled.
        sensor.setLedCurrent(1, 8)                                                               // LED drive, (led, level) → Unit
        // Level 0 = off … 15 = maximum (~359 mA); keep the gain low at high currents.
        sensor.setPsLed(1, 0x01)                                                                 // PS LED select, (channel, leds) → Unit
        // PS1 drives LED1; the Si1145 has no LED2/LED3.
        sensor.setPsPhotodiode(1, true)                                                          // PS photodiode, (channel, large) → Unit
        // Large IR photodiode (0x03); false selects the small one.
        sensor.setGain(0, 0, 0)                                                                  // ADC gains, (visible, infrared, proximity) → Unit
        // Integration time × 2^n, recovery counts at the one's complement of the gain.
        sensor.setHighRange(false, false, false)                                                 // Signal range, (visible, infrared, proximity) → Unit
        // true divides the sensitivity by 14.5 (direct sunlight).
        sensor.setAlignment(false, false, false, false, false)                                   // Result alignment, (visible, infrared, ps1, ps2, ps3) → Unit
        // true reports the 16 LSBs instead of the 16 MSBs of the ADC result.
        sensor.setUvCoefficients(Si1145Minimal.UCOEF_DEFAULT)                                    // UCOEF0..3, (ucoef int[4]) → Unit
        // 7B 6B 01 00 make AUX_DATA the UV index × 100.
        println(sensor.readCalibration().contentToString())                                      // GET_CAL, () → IntArray
        // Factory calibration bytes; overwrites the data registers.

        sensor.pollInterrupt()                                                                   // IRQ_STATUS, () → Int sources
        // Reads and clears pending flags.
        sensor.startAutonomous(0.1, true, true)                                                  // Autonomous mode, (intervalS, proximity=true, als=true) → Unit
        // MEAS_RATE = 0.1 s / 31.25 µs = 3200, then PSALS_AUTO.
        Thread.sleep(500)
        System.out.printf("sources 0x%02X%n", sensor.pollInterrupt())                            // IRQ_STATUS, () → Int sources
        // SOURCE_ALS | SOURCE_PS1 when both finished at least once.
        sensor.pause()                                                                           // PSALS_PAUSE, () → Unit
        // Stops the autonomous loop.
        sensor.force(true, false)                                                                // Forced measurement without waiting, (proximity=true, als=true) → Unit
        // PS_FORCE; the result arrives when IRQ_STATUS flags PS1.
        Thread.sleep(50)

        val events = java.util.Collections.synchronizedList(mutableListOf<Int>())
        sensor.enableInterrupt(Si1145Minimal.SOURCE_ALS or Si1145Minimal.SOURCE_PS1)              // INT_CFG + IRQ_ENABLE, (source) → Unit
        // INT pin (active low) driven for ALS and PS1 completions.
        sensor.onInterrupt(IntConsumer { events.add(it) })                                       // Subscribe, (callback, intPin=connection.intPin()) → Unit
        // Falling edge on the connection's INT pin, otherwise a 10 ms polling thread.
        sensor.force()                                                                           // PSALS_FORCE without waiting, () → Unit
        Thread.sleep(200)
        sensor.offInterrupt()                                                                    // Unsubscribe, () → Unit
        println(events)
        sensor.disableInterrupt(Si1145Minimal.SOURCE_CMD)                                        // IRQ_ENABLE clear, (source) → Unit
        // CMD_INT is not used by default.

        sensor.setI2cAddress(0x60)                                                               // I2C_ADDR + BUSADDR, (address) → Unit
        // Volatile; here the address stays 0x60, otherwise reconnect on the new address.
        sensor.reset()                                                                           // RESET + HW_KEY + defaults, () → Unit
        // Software reset, 10 ms wait, then the Minimal configuration again.
    }
}
