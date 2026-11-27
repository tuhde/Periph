///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Mpl3115a2Full
import java.util.function.IntConsumer

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Mpl3115a2Full(connection)                                                      // Create MPL3115A2 driver, (connection) → Mpl3115a2Full
        sensor.init()                                                                               // Reset and apply defaults, () → void
                                                                                                    // 0xC4 identity check, PT_DATA_CFG = 0x07, barometer, OSR 128
        val id = sensor.whoAmI()                                                                    // Read WHO_AM_I, () → int (0xC4)
        System.out.printf("who_am_i 0x%02X%n", id)
        sensor.setOversample(32)                                                                    // Set the oversample ratio, (ratio 1/2/4/…/128) → void
                                                                                                    // 128 gives 0.25 Pa resolution at 512 ms per sample
        val ratio = sensor.getOversample()                                                          // Get the oversample ratio, () → int
        System.out.println("oversample " + ratio)
        sensor.setSeaLevelPressure(101325.0)                                                        // Set the sea-level reference, (pa) → void
                                                                                                    // BAR_IN in 2 Pa units
        sensor.setOffsets(0.0, 0.0, 0)                                                              // Trim the offsets, (pressurePa, temperatureC, altitudeM) → void
        val p = sensor.readPressure()                                                               // One-shot pressure, () → double Pa
        val t = sensor.readTemperature()                                                            // One-shot temperature, () → double °C
        val both = sensor.read()                                                                    // Both together, () → double[] {Pa, °C}
        System.out.printf("%.2f Pa %.2f C (%.2f %.2f)%n", both[0], both[1], p, t)
        val alt = sensor.readAltitude()                                                             // Altimeter reading, () → double m
                                                                                                    // switches to altimeter mode
        System.out.printf("altitude %.2f m%n", alt)
        sensor.setMode(false)                                                                       // Back to barometer mode, (altimeter) → void
        sensor.startContinuous(0)                                                                   // Start periodic acquisition, (stepExp 0-15) → void
                                                                                                    // one sample every 2^n seconds
        val active = sensor.isActive()                                                              // Check ACTIVE mode, () → boolean
        System.out.println("active " + active)
        val cont = sensor.readContinuous()                                                          // Wait for PTDR and read, () → double[] {value, °C}
        System.out.printf("continuous %.2f %.2f%n", cont[0], cont[1])
        val ready = sensor.dataReady()                                                              // Data-ready flag, () → boolean
        val delta = sensor.readDelta()                                                              // Change since the previous sample, () → double[] {delta value, delta °C}
        System.out.printf("ready %b delta %.2f %.4f%n", ready, delta[0], delta[1])
        sensor.stopContinuous()                                                                     // Back to STANDBY, () → void
        sensor.setTarget(100000.0, 200.0, -5, 3)                                                    // Threshold targets, (value, window, temperatureC, temperatureWindow) → void
        sensor.loadCurrentAsTarget()                                                                // Use the current output as target, () → void
        val mm = sensor.readMinMax()                                                                // Captured extremes, () → double[] {pMin, pMax, tMin, tMax}
        System.out.printf("min %.2f max %.2f%n", mm[0], mm[1])
        sensor.clearMinMax()                                                                        // Clear the extremes, () → void
        sensor.fifoSetup(Mpl3115a2Full.FIFO_CIRCULAR, 16)                                           // Configure the FIFO, (mode 0/1/2, watermark 0-63) → void
        val fs = sensor.fifoStatus()                                                                // FIFO state, () → int[] {overflow, watermark, count}
        System.out.println("fifo count " + fs[2])
        val samples = sensor.fifoRead()                                                             // Drain the FIFO, () → List<double[]> {value, °C}
        System.out.println("fifo samples " + samples.size)
        sensor.fifoSetup(Mpl3115a2Full.FIFO_OFF, 0)                                                 // FIFO off, (mode, watermark) → void
        sensor.setInterruptPins(false, false, false, false)                                         // INT polarity / drive, (polarity1, openDrain1, polarity2, openDrain2) → void
        sensor.enableInterrupt(Mpl3115a2Full.SOURCE_DRDY, 1)                                        // Route a source to INT1, (source, pin 1/2) → void
        val src = sensor.pollInterrupt()                                                            // Read INT_SOURCE, () → int
        System.out.printf("INT_SOURCE 0x%02X%n", src)
        sensor.onInterrupt(IntConsumer { status -> System.out.printf("INT 0x%02X%n", status) }, 1)  // Subscribe to INT1 (100 ms polling without a wired pin), (callback, pin) → void
        Thread.sleep(1000)
        sensor.offInterrupt()                                                                       // Unsubscribe, () → void
        sensor.disableInterrupt(Mpl3115a2Full.SOURCE_DRDY)                                          // Disable the source, (source) → void
        sensor.reset()                                                                              // Software reset and defaults, () → void
    }
}
