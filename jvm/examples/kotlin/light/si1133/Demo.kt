///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1133Full
import it.uhde.periph.chips.light.Si1133Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x55").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Si1133Full(connection)                                                         // Create Si1133 driver, (connection) → Si1133Full
        sensor.init()                                                                               // Check PART_ID, reset, UV + white channels, () → void

        // --- Identity ---
        // PART_ID 0x33 identifies the Si1133; HW_ID and REV_ID give silicon and
        // product revision. RESPONSE0 shows the sequencer state and command counter.
        val id = sensor.readId()                                                                    // Identity registers, () → Id {partId, hwId, revId}
        println("PART_ID 0x%02X  HW_ID 0x%02X  REV_ID 0x%02X".format(id.partId, id.hwId, id.revId))
        println("response " + sensor.readResponse())                                                // RESPONSE0 decoded, () → Response

        // --- UV and daylight monitor: 20 s at 1 Hz ---
        // The counts are raw ADC values; a UV index needs Silicon Labs' calibration
        // for the product's window, so watch the trend.
        for (i in 0 until 20) {
            val m = sensor.read()                                                                   // Forced measurement, () → Reading {uv counts, white counts}
            println("%2d s  UV %8d  white %8d".format(i, m.uv, m.white))
            Thread.sleep(1000)
        }

        // --- Gain settings on a constant light level ---
        // HW_GAIN doubles the integration time per step, SW_GAIN accumulates 2^n
        // samples, HIGH_SIGNAL divides the sensitivity by 14.5 for direct sunlight.
        sensor.enableChannels(0x04)                                                                 // CHAN_LIST, (mask) → void
        val gains = listOf(intArrayOf(2, 0, 0), intArrayOf(6, 0, 0), intArrayOf(6, 2, 0), intArrayOf(6, 0, 1))
        for (g in gains) {
            sensor.configureChannel(2, Si1133Minimal.MUX_WHITE, g[0], g[1], 0, g[2] != 0, true, 0)  // Channel setup, (channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24, postshift) → void
            val value = sensor.readChannels()[0]                                                    // Forced read, () → int[]
            println("hwGain ${g[0]} swGain ${g[1]} highSignal ${g[2] != 0} -> $value")
        }

        // --- IR photodiodes ---
        val names = listOf("small", "medium", "large")
        val muxes = listOf(Si1133Minimal.MUX_SMALL_IR, Si1133Minimal.MUX_MEDIUM_IR, Si1133Minimal.MUX_LARGE_IR)
        for (i in 0 until 3) {
            println("IR %-6s %d".format(names[i], sensor.readIr(muxes[i])))                         // IR photodiode once, (photodiode) → int counts
        }
        println("UV ${sensor.readUv()}, white ${sensor.readWhite()}")                               // Scratch-channel reads, () → int counts

        // --- Autonomous mode with a UV threshold ---
        // Channels 0 and 1 run every second; channel 0 only interrupts when crossing
        // THRESHOLD0, channel 1 on every result.
        sensor.configureChannel(0, Si1133Minimal.MUX_UV, 9)                                         // Channel setup, (channel, adcmux, hwGain) → void
        sensor.enableChannels(0x03)                                                                 // CHAN_LIST, (mask) → void
        sensor.setThreshold(0, 500)                                                                 // Threshold value, (index, value) → void
        sensor.setChannelThreshold(0, 1)                                                            // Threshold select, (channel, index) → void
        sensor.setChannelCounter(1, 1)                                                              // Counter for a channel, (channel, index) → void
        sensor.disableInterrupt(Si1133Full.SOURCE_CHANNEL1)                                         // IRQ_ENABLE clear, (source) → void
        sensor.enableInterrupt(Si1133Full.SOURCE_CHANNEL0)                                          // IRQ_ENABLE set, (source) → void
        sensor.onInterrupt({ status ->                                                              // Subscribe, (callback) → void
            println("interrupt 0x%02X values %s".format(status, sensor.readChannels(false).contentToString()))
        })
        sensor.startAutonomous(0.1, intArrayOf(10, 10, 10))                                         // Autonomous mode, (rateS, counters) → void
        Thread.sleep(5000)
        sensor.offInterrupt()                                                                       // Unsubscribe, () → void
        sensor.pause()                                                                              // PAUSE, () → void
        sensor.enableInterrupt(Si1133Full.SOURCE_CHANNEL1)                                          // IRQ_ENABLE set, (source) → void

        // --- Burst mode: four readings in a row ---
        sensor.setBurst(4)                                                                          // Burst mode, (count) → void
        sensor.force()                                                                              // FORCE without waiting, () → void
        Thread.sleep(500)
        println("burst ready 0x%02X".format(sensor.dataReady()))                                    // IRQ_STATUS mask, () → int
        println("burst values " + sensor.readChannels(false).contentToString())                     // Decode current results, (force=false) → int[]
        sensor.setBurst(0)

        // --- Parameter table ---
        sensor.writeParameter(0x1E, 7)                                                              // PARAM_SET, (address, value) → void
        val chan = sensor.readParameter(0x01)                                                       // PARAM_QUERY, (address) → int
        val rate = (sensor.readParameter(0x1A) shl 8) or sensor.readParameter(0x1B)
        println("CHAN_LIST 0x%02X  MEASRATE %d  MEASCOUNT2 %d".format(chan, rate, sensor.readParameter(0x1E)))
        println("poll " + sensor.pollInterrupt())                                                   // IRQ_STATUS mask, () → int

        // --- Finish ---
        // setI2cAddress would move the chip to another address until the next
        // reset; the demo keeps 0x55 and only shows the call.
        // sensor.setI2cAddress(0x56)                                                          // Change address, (address) → void
        sensor.reset()                                                                              // RESET_SW + defaults, () → void
        println("after reset " + sensor.read())                                                     // Forced measurement, () → Reading
    }
}
