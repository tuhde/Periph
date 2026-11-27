///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9250Minimal
import it.uhde.periph.chips.light.Apds9250Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, Apds9250Minimal.DEFAULT_ADDRESS)                           // open I²C bus, device address, (bus, address=0x52) → I2CConnection
try {
    def sensor = new Apds9250Full(connection)                                                       // construct driver, (connection) → Apds9250Full

    // --- Colour sampler ---
    // Prints the part ID and settings, samples R/G/B/IR as a normalised colour,
    // compares ALS/IR mode, steps gain and resolution with normalised counts,
    // arms a variation and a threshold interrupt on the green channel (polled
    // through pollInterrupt), then disables the sensor and soft-resets it.
    println("part ${sensor.partId()[0]} resolution ${sensor.getResolution()} bit rate ${sensor.getMeasurementRate()} ms gain ${sensor.getGain()}")// Read PART_ID and the settings, () → ints
    for (int i = 0; i < 20; i++) {
        def c = sensor.read()                                                                       // Read the four channels, () → Counts
        def top = Math.max(Math.max(c.red, c.green), Math.max(c.blue, 1))
        println("#${String.format("%02x%02x%02x", (long) c.red * 255 / top, (long) c.green * 255 / top, (long) c.blue * 255 / top)} IR ${c.ir}")
        Thread.sleep(500)
    }
    sensor.setMode(false)                                                                           // Select ALS/IR mode, (colour) → void
    println("ALS/IR green ${sensor.readGreen()} ir ${sensor.readIr()}")
    sensor.setMode(true)                                                                            // Back to colour mode, (colour) → void
    for (int gain : [1, 3, 6, 9, 18]) {
        sensor.setGain(gain)                                                                        // Set the gain, (gain) → void
        Thread.sleep(300)
        def g = sensor.readGreen()
        println("gain ${gain} green ${g} normalized ${Math.round(sensor.normalized(g))}")           // Counts per gain-second, (counts) → double
    }
    sensor.setGain(3)
    for (int bits : [13, 16, 17, 18, 19, 20]) {
        sensor.setResolution(bits)                                                                  // Set the resolution, (bits) → void
        Thread.sleep(500)
        def g = sensor.readGreen()
        println("${bits} bit full scale ${(1 << bits) - 1} green ${g} normalized ${Math.round(sensor.normalized(g))}")
    }
    sensor.setResolution(18)
    sensor.setMeasurementRate(100)                                                                  // Set the rate, (ms) → void
    sensor.setInterruptChannel(Apds9250Full.CHANNEL_GREEN)                                          // Choose the channel, (channel) → void
    sensor.setPersistence(2)                                                                        // Set the persistence, (count) → void
    sensor.setVariance(64)                                                                          // Set the variation threshold, (counts) → void
    sensor.enableInterrupt(Apds9250Full.SOURCE_VARIATION)                                           // Enable the variation interrupt, (source) → void
    for (int i = 0; i < 20; i++) {
        Thread.sleep(500)
        def status = sensor.pollInterrupt()                                                         // Read and clear MAIN_STATUS, () → int
        if ((status & Apds9250Minimal.STATUS_INTERRUPT) != 0) {
            println("variation interrupt, green ${sensor.readGreen()}")
        }
    }
    def base = sensor.readGreen()
    sensor.setThresholds(base - base / 4, base + base / 4)                                          // Set the window, (low, high) → void
    sensor.enableInterrupt(Apds9250Full.SOURCE_THRESHOLD)                                           // Enable the threshold interrupt, (source) → void
    Thread.sleep(5000)
    println("status ${sensor.pollInterrupt()}")
    sensor.disableInterrupt()                                                                       // Disable the interrupt, () → void
    sensor.disable()                                                                                // Standby, () → void
    sensor.softReset()                                                                              // Software reset, () → void
} finally {
    connection.close()
}
