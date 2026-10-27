///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import it.uhde.periph.chips.accelerometer.LSM303Minimal
import it.uhde.periph.chips.accelerometer.LSM303Source

def bus = System.getenv().getOrDefault("I2C_BUS", "1") as int
def accelConn = new I2CConnection(bus, 0x19)
def magConn = new I2CConnection(bus, 0x1E)
try {
    def chip = new LSM303Full(accelConn, magConn)               // Create LSM303 Full driver, (accelConnection, magConnection) → void

    chip.setRange(8)                                            // Set accelerometer range, (rangeG) → void g
    chip.setMode(LSM303Minimal.MODE_NORMAL)                     // Set accelerometer mode, (mode) → void
    chip.setDataRate(400)                                       // Set accelerometer data rate, (rateHz) → void Hz
    chip.setAxes(true, true, true)                              // Enable accelerometer axes, (x, y, z) → void
    chip.powerDown()                                            // Stop accelerometer conversions, () → void
    chip.setDataRate(100)                                       // Set accelerometer data rate, (rateHz) → void Hz
    chip.setMode(LSM303Minimal.MODE_HIGH_RESOLUTION)            // Set accelerometer mode, (mode) → void
    chip.setRange(2)                                            // Set accelerometer range, (rangeG) → void g

    def a = chip.readAcceleration()                             // Read 3-axis acceleration, () → double[] g
    def raw = chip.readAccelerationRaw()                        // Read raw acceleration counts, () → int[] LSB
    def ready = chip.newDataAvailable()                         // Check new acceleration data, () → boolean
    def status = chip.readStatus()                              // Read accelerometer status, () → int
    chip.setHighPass(1, 2, true, true, false, true)             // Configure high-pass filter, (...) → void
    def reference = chip.resetHighPass()                        // Reset high-pass filter, () → int
    chip.setReference(0x40)                                     // Set high-pass reference, (value) → void
    chip.setFifo(LSM303Full.FIFO_FIFO, 16, false)               // Configure FIFO, (mode, watermark, triggerInt2) → void
    def level = chip.fifoLevel()                                // Read FIFO level, () → int
    def fifoStatus = chip.readFifoStatus()                      // Read FIFO status, () → Object[]
    def samples = chip.readFifo(-1)                             // Drain FIFO, (count) → double[][]
    chip.setFifo(LSM303Full.FIFO_BYPASS, 0, false)              // Configure FIFO, (mode, watermark, triggerInt2) → void
    chip.setMotionInterrupt(1, 0x3F, 0.5, 30d, true)            // Configure motion interrupt, (...) → void
    chip.setFreeFall(1, 0.35d, 30d)                             // Configure free-fall, (...) → void
    chip.setOrientation(1, false, false)                        // Configure orientation, (...) → void
    chip.setLatch(1, true)                                      // Latch generator 1, (generator, enabled) → void
    def src = chip.readInterruptSource(1)                       // Read interrupt source, (generator) → int
    chip.setLatch(1, false)                                     // Latch generator 1, (generator, enabled) → void
    chip.setClick(0x01, 0x04, 0.5d, 10d, 20d, 50d)             // Configure click detection, (...) → void
    def click = chip.readClickSource()                          // Read click source, () → int
    chip.setInterruptPolarity(false)                            // Set interrupt polarity, (activeLow) → void
    chip.enableInterrupt(LSM303Source.SOURCE_DATA_READY, 1)     // Enable interrupt source, (source, pin) → void
    chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 1)          // Enable interrupt source, (source, pin) → void
    chip.disableInterrupt(LSM303Source.SOURCE_DATA_READY, 1)    // Disable interrupt source, (source, pin) → void
    def isr = chip.pollInterrupt()                              // Poll interrupt status, () → int
    chip.setMagRange(470)                                       // Set magnetometer range, (rangeUt) → void µT
    chip.setMagDataRate(30d)                                    // Set magnetometer data rate, (rateHz) → void Hz
    chip.setMagMode(1)                                          // Set magnetometer mode, (mode) → void
    chip.setMagMode(0)                                          // Set magnetometer mode, (mode) → void
    def mraw = chip.readMagneticRaw()                           // Read raw magnetic counts, () → int[] LSB
    def m = chip.readMagnetic()                                 // Read 3-axis magnetic field, () → double[] µT
    def mready = chip.magDataReady()                            // Check new magnetometer data, () → boolean
    def mstatus = chip.readMagStatus()                          // Read magnetometer status, () → boolean[]
    def id = chip.readMagIdentity()                             // Read magnetometer identity, () → byte[]
    chip.enableTemperature(true)                                // Enable temperature sensor, (enabled) → void
    def temp = chip.readTemperatureDelta()                      // Read relative temperature, () → double °C
    def heading = chip.heading(true)                            // Compute compass heading, (tiltCompensate) → double deg
    def flat = chip.heading(false)                              // Compute compass heading, (tiltCompensate) → double deg
    chip.reboot()                                               // Reboot accelerometer memory, () → void

    printf("accel=%.3f %.3f %.3f g raw=%d %d %d ready=%b status=0x%02X%n", a[0], a[1], a[2], raw[0], raw[1], raw[2], ready, status)
    printf("reference=0x%02X fifo=%d samples=%d%n", reference, level, samples.length)
    printf("src=0x%02X click=0x%02X isr=0x%04X%n", src, click, isr)
    printf("mag=%.1f %.1f %.1f µT raw=%d %d %d ready=%b%n", m[0], m[1], m[2], mraw[0], mraw[1], mraw[2], mready)
    printf("temp=%+.1f °C heading=%.1f° flat=%.1f°%n", temp, heading, flat)
} finally {
    accelConn.close()
    magConn.close()
}
