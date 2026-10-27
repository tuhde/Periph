///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import it.uhde.periph.chips.accelerometer.LSM303Minimal
import it.uhde.periph.chips.accelerometer.LSM303Source

fun main() {
    val bus = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    I2CConnection(bus, 0x19).use { accelConnection ->
    I2CConnection(bus, 0x1E).use { magConnection ->
        val chip = LSM303Full(accelConnection, magConnection)       // Create LSM303 Full driver, (accelConnection, magConnection) → void

        chip.setRange(8)                                            // Set accelerometer range, (rangeG) → void g
        chip.setMode(LSM303Minimal.MODE_NORMAL)                     // Set accelerometer mode, (mode) → void
        chip.setDataRate(400)                                       // Set accelerometer data rate, (rateHz) → void Hz
        chip.setAxes(true, true, true)                              // Enable accelerometer axes, (x, y, z) → void
        chip.powerDown()                                            // Stop accelerometer conversions, () → void
        chip.setDataRate(100)                                       // Set accelerometer data rate, (rateHz) → void Hz
        chip.setMode(LSM303Minimal.MODE_HIGH_RESOLUTION)            // Set accelerometer mode, (mode) → void
        chip.setRange(2)                                            // Set accelerometer range, (rangeG) → void g

        val a = chip.readAcceleration()                             // Read 3-axis acceleration, () → DoubleArray g
        val raw = chip.readAccelerationRaw()                        // Read raw acceleration counts, () → IntArray LSB
        val ready = chip.newDataAvailable()                         // Check new acceleration data, () → Boolean
        val status = chip.readStatus()                              // Read accelerometer status, () → Int
        chip.setHighPass(1, 2, true, true, false, true)             // Configure high-pass filter, (...) → void
        val reference = chip.resetHighPass()                        // Reset high-pass filter, () → Int
        chip.setReference(0x40)                                     // Set high-pass reference, (value) → void
        chip.setFifo(LSM303Full.FIFO_FIFO, 16, false)               // Configure FIFO, (mode, watermark, triggerInt2) → void
        val level = chip.fifoLevel()                                // Read FIFO level, () → Int
        val fifoStatus = chip.readFifoStatus()                      // Read FIFO status, () → Array<Any>
        val samples = chip.readFifo(-1)                             // Drain FIFO, (count) → Array<DoubleArray>
        chip.setFifo(LSM303Full.FIFO_BYPASS, 0, false)              // Configure FIFO, (mode, watermark, triggerInt2) → void
        chip.setMotionInterrupt(1, 0x3F, 0.5, 30.0, true)           // Configure motion interrupt, (...) → void
        chip.setFreeFall(1, 0.35, 30.0)                             // Configure free-fall, (...) → void
        chip.setOrientation(1, false, false)                        // Configure orientation, (...) → void
        chip.setLatch(1, true)                                      // Latch generator 1, (generator, enabled) → void
        val src = chip.readInterruptSource(1)                       // Read interrupt source, (generator) → Int
        chip.setLatch(1, false)                                     // Latch generator 1, (generator, enabled) → void
        chip.setClick(0x01, 0x04, 0.5, 10.0, 20.0, 50.0)            // Configure click detection, (...) → void
        val click = chip.readClickSource()                          // Read click source, () → Int
        chip.setInterruptPolarity(false)                            // Set interrupt polarity, (activeLow) → void
        chip.enableInterrupt(LSM303Source.SOURCE_DATA_READY, 1)     // Enable interrupt source, (source, pin) → void
        chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 1)          // Enable interrupt source, (source, pin) → void
        chip.disableInterrupt(LSM303Source.SOURCE_DATA_READY, 1)    // Disable interrupt source, (source, pin) → void
        val isr = chip.pollInterrupt()                              // Poll interrupt status, () → Int
        chip.setMagRange(470)                                       // Set magnetometer range, (rangeUt) → void µT
        chip.setMagDataRate(30.0)                                   // Set magnetometer data rate, (rateHz) → void Hz
        chip.setMagMode(1)                                          // Set magnetometer mode, (mode) → void
        chip.setMagMode(0)                                          // Set magnetometer mode, (mode) → void
        val mraw = chip.readMagneticRaw()                           // Read raw magnetic counts, () → IntArray LSB
        val m = chip.readMagnetic()                                 // Read 3-axis magnetic field, () → DoubleArray µT
        val mready = chip.magDataReady()                            // Check new magnetometer data, () → Boolean
        val mstatus = chip.readMagStatus()                          // Read magnetometer status, () → BooleanArray
        val id = chip.readMagIdentity()                             // Read magnetometer identity, () → ByteArray
        chip.enableTemperature(true)                                // Enable temperature sensor, (enabled) → void
        val temp = chip.readTemperatureDelta()                      // Read relative temperature, () → Double °C
        val heading = chip.heading(true)                            // Compute compass heading, (tiltCompensate) → Double deg
        val flat = chip.heading(false)                              // Compute compass heading, (tiltCompensate) → Double deg
        chip.reboot()                                               // Reboot accelerometer memory, () → void

        println("accel=%.3f %.3f %.3f g raw=%d %d %d ready=%b status=0x%02X".format(a[0], a[1], a[2], raw[0], raw[1], raw[2], ready, status))
        println("reference=0x%02X fifo=%d samples=%d".format(reference, level, samples.size))
        println("src=0x%02X click=0x%02X isr=0x%04X".format(src, click, isr))
        println("mag=%.1f %.1f %.1f µT raw=%d %d %d ready=%b".format(m[0], m[1], m[2], mraw[0], mraw[1], mraw[2], mready))
        println("temp=%+.1f °C heading=%.1f° flat=%.1f°".format(temp, heading, flat))
    }
    }
}
