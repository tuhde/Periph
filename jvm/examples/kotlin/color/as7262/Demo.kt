///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.color.As7262Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x49").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = As7262Full(connection)                                                         // Create AS7262 driver, (connection) → void
        sensor.init()                                                                               // Reset and configure defaults, () → void

        // --- Spectrometer ---
        // Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
        val hw = sensor.hardwareVersion()                                                           // Hardware version, () → int[] {type, version}
        val fw = sensor.firmwareVersion()                                                           // Firmware version, () → int[] {major, minor, sub}
        println("type 0x%X hw 0x%X fw %d.%d.%d".format(sensor.deviceType(), hw[1], fw[0], fw[1], fw[2]))
        println("chip %.0f C".format(sensor.temperature()))
        for (gain in doubleArrayOf(1.0, 3.7, 16.0, 64.0)) {
            sensor.setGain(gain)                                                                    // Set gain, (gain) → void
            val cal = sensor.read()                                                                 // Read calibrated, () → Channels
            val raw = sensor.readRaw()                                                              // Read raw, () → RawChannels
            println("gain %.1f V %.2f raw V %d".format(sensor.getGain(), cal.violet, raw.violet))
        }
        sensor.setGain(16.0)                                                                        // Set gain, (gain) → void
        for (ms in doubleArrayOf(50.0, 500.0)) {
            sensor.setIntegrationTime(ms)                                                           // Set integration time, (ms) → void
            val t0 = System.currentTimeMillis()
            sensor.read()                                                                           // One-shot read, () → Channels
            println("integration %.1f ms took %d ms".format(sensor.getIntegrationTime(), System.currentTimeMillis() - t0))
        }
        sensor.setIntegrationTime(165.2)                                                            // Set integration time, (ms) → void
        sensor.setMode(3)                                                                           // Set bank mode, (mode) → void
        val base = sensor.read().toArray()                                                          // Read calibrated, () → Channels
        sensor.setIndicatorLed(true, 4.0)                                                           // Indicator LED, (on, currentMa) → void
        sensor.setLed(true, 25.0)                                                                   // Driver LED, (on, currentMa) → void
        val lit = sensor.read().toArray()                                                           // Read calibrated, () → Channels
        sensor.setLed(false, 25.0)                                                                  // Driver LED off, (on, currentMa) → void
        sensor.setIndicatorLed(false, 4.0)                                                          // Indicator LED off, (on, currentMa) → void
        var best = 0
        for (i in 1 until 6) if (lit[i] - base[i] > lit[best] - base[best]) best = i
        println("reflectance dominant channel %d".format(best))
        sensor.measure()                                                                            // Start one-shot, () → void
        while (!sensor.dataReady()) Thread.sleep(50)                                                // Data ready, () → boolean
        val last = sensor.readCalibratedLast()                                                      // Last calibrated results, () → Channels
        val lastRaw = sensor.readRawLast()                                                          // Last raw counts, () → RawChannels
        println("last V %.2f raw V %d channel 0 %.2f".format(last.violet, lastRaw.violet, sensor.readChannel(0)))
        sensor.setContinuous(2)                                                                     // Continuous mode, (mode) → void
        sensor.onInterrupt({ println(sensor.readCalibratedLast().violet) })                         // Subscribe to INT, (callback) → void
        Thread.sleep(5000)
        sensor.offInterrupt()                                                                       // Unsubscribe, () → void
        sensor.setMode(3)                                                                           // Back to one-shot, (mode) → void
        sensor.writeVirtual(0x05, 0x3B)                                                             // Write virtual register, (reg, value) → void
        println("INT_T %d".format(sensor.readVirtual(0x05)))
        sensor.softReset()                                                                          // Soft reset, () → void
    }
}
