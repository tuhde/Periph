///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.color.As7262Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x49").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = As7262Full(connection)                                                         // Create AS7262 driver, (connection) → void
        sensor.init()                                                                               // Reset and configure defaults, () → void

        val ch = sensor.read()                                                                      // Read calibrated channels, () → Channels
                                                                                                    // one one-shot conversion
        println("V %.2f B %.2f G %.2f Y %.2f O %.2f R %.2f".format(ch.violet, ch.blue, ch.green, ch.yellow, ch.orange, ch.red))
        val raw = sensor.readRaw()                                                                  // Read raw counts, () → RawChannels
                                                                                                    // 16-bit counts
        println("raw V %d R %d".format(raw.violet, raw.red))
        val t = sensor.temperature()                                                                // Read device temperature, () → double °C
        println("%.0f C".format(t))
        sensor.measure()                                                                            // Start a one-shot conversion, () → void
                                                                                                    // does not wait
        while (!sensor.dataReady()) Thread.sleep(50)                                                // Check data ready, () → boolean
        val last = sensor.readCalibratedLast()                                                      // Read last calibrated results, () → Channels
        val lastRaw = sensor.readRawLast()                                                          // Read last raw counts, () → RawChannels
        val c5 = sensor.readChannel(5)                                                              // Read one channel, (index) → float
                                                                                                    // 0..5 = V, B, G, Y, O, R
        val c5raw = sensor.readChannelRaw(5)                                                        // Read one raw channel, (index) → int
        println("channel 5 %.2f raw %d".format(c5, c5raw))
        sensor.setGain(16.0)                                                                        // Set gain, (gain) → void
                                                                                                    // 0-3 index or 1, 3.7, 16, 64
        println("gain %.1f".format(sensor.getGain()))
        sensor.setIntegrationTime(165.2)                                                            // Set integration time, (ms) → void
                                                                                                    // 2.8 - 714 ms
        println("integration %.1f ms".format(sensor.getIntegrationTime()))
        sensor.setMode(3)                                                                           // Set bank mode, (mode) → void
                                                                                                    // 0, 1, 2 continuous; 3 one-shot
        println("mode %d".format(sensor.getMode()))
        sensor.setContinuous(2)                                                                     // Continuous conversions, (mode) → void
        sensor.setMode(3)                                                                           // Back to one-shot, (mode) → void
        sensor.setInterrupt(false)                                                                  // Enable/disable INT pin, (enabled) → void
        sensor.setLed(true, 25.0)                                                                   // Driver LED, (on, currentMa) → void
                                                                                                    // 12.5, 25, 50 or 100 mA
        sensor.setLed(false, 25.0)                                                                  // Driver LED off, (on, currentMa) → void
        sensor.setIndicatorLed(true, 4.0)                                                           // Indicator LED, (on, currentMa) → void
                                                                                                    // 1, 2, 4 or 8 mA
        sensor.setIndicatorLed(false, 4.0)                                                          // Indicator LED off, (on, currentMa) → void
        val hw = sensor.hardwareVersion()                                                           // Hardware version, () → int[] {type, version}
        val fw = sensor.firmwareVersion()                                                           // Firmware version, () → int[] {major, minor, sub}
        println("type 0x%X hw 0x%X fw %d.%d.%d".format(sensor.deviceType(), hw[1], fw[0], fw[1], fw[2]))
        val reg = sensor.readVirtual(0x05)                                                          // Read a virtual register, (reg) → int
        sensor.writeVirtual(0x05, 0x3B)                                                             // Write a virtual register, (reg, value) → void
        println("INT_T %d".format(reg))
        sensor.setContinuous(2)                                                                     // Continuous conversions, (mode) → void
        sensor.onInterrupt({ println("data ready") })                                               // Subscribe to INT, (callback) → void
                                                                                                    // falls back to polling without an INT pin
        Thread.sleep(3000)
        println("data ready %d".format(sensor.pollInterrupt()))
        sensor.offInterrupt()                                                                       // Unsubscribe, () → void
        sensor.softReset()                                                                          // Soft reset, () → void
                                                                                                    // restores defaults
    }
}
