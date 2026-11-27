///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.color.Isl29125Minimal
import it.uhde.periph.chips.color.Isl29125Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, Isl29125Minimal.DEFAULT_ADDRESS)                           // open I²C bus, device address, (bus, address=0x44) → I2CConnection
try {
    def sensor = new Isl29125Full(connection)                                                       // construct driver, (connection) → Isl29125Full

    // --- Colour sampler ---
    // Prints the device ID, samples R/G/B as a normalised colour, compares the
    // 375 lux and 10 000 lux ranges and the 12/16-bit resolutions, varies the IR
    // compensation, reads green only, arms a threshold interrupt on green with
    // persistence 2 (polled through pollInterrupt), then powers down and resets.
    println("device id ${sensor.deviceId()}")                                                       // Read DEVICE_ID, () → int
    for (int i = 0; i < 20; i++) {
        def c = sensor.read()                                                                       // Read the three channels, () → Counts
        def top = Math.max(Math.max(c.red, c.green), Math.max(c.blue, 1))
        println("#${String.format("%02x%02x%02x", (long) c.red * 255 / top, (long) c.green * 255 / top, (long) c.blue * 255 / top)} ${c.red} ${c.green} ${c.blue}")
        Thread.sleep(500)
    }
    for (int lux : [375, 10000]) {
        sensor.setRange(lux)                                                                        // Set the range, (lux) → void
        Thread.sleep(400)
        def r = sensor.readRgb()                                                                    // Read without waiting, () → Counts
        println("${lux} lux range ${r.red} ${r.green} ${r.blue}")
    }
    for (int bits : [12, 16]) {
        sensor.setResolution(bits)                                                                  // Set the resolution, (bits) → void
        Thread.sleep(400)
        def r = sensor.readRgb()
        println("${bits} bit full scale ${(1 << bits) - 1} ${r.red} ${r.green} ${r.blue}")
    }
    sensor.setRange(10000)
    for (int adjust : [0, 0x20, 0x3F]) {
        sensor.setIrCompensation(false, adjust)                                                     // Set the IR compensation, (offset, adjust) → void
        Thread.sleep(400)
        def r = sensor.readRgb()
        println("IR compensation ${adjust} ${r.red} ${r.green} ${r.blue}")
    }
    sensor.setIrCompensation(true, 0x3F)
    sensor.setMode(Isl29125Full.MODE_GREEN)                                                         // Green only, (mode) → void
    Thread.sleep(300)
    println("green only ${sensor.readGreen()}")                                                     // Read green, () → int
    sensor.setMode(Isl29125Full.MODE_RGB)
    println("ready ${sensor.dataReady()}")                                                          // Query CONVENF, () → boolean
    def base = sensor.readGreen()
    sensor.setThresholds(base - base / 4, Math.min(0xFFFF, base + base / 4))                        // Set the window, (low, high) → void
    println("window ${sensor.getThresholds()[0]} - ${sensor.getThresholds()[1]}")                   // Read the window, () → (low, high)
    sensor.setInterruptChannel(Isl29125Full.CHANNEL_GREEN)                                          // Choose the channel, (channel) → void
    sensor.setPersistence(2)                                                                        // Set the persistence, (count) → void
    sensor.enableInterrupt(Isl29125Full.SOURCE_THRESHOLD)                                           // Enable the interrupt, (source) → void
    for (int i = 0; i < 20; i++) {
        Thread.sleep(500)
        def status = sensor.pollInterrupt()                                                         // Read and clear STATUS, () → int
        if ((status & Isl29125Full.STATUS_THRESHOLD) != 0) {
            println("window crossed, green ${sensor.readGreen()}")
        }
    }
    sensor.disableInterrupt(Isl29125Full.SOURCE_THRESHOLD)                                          // Disable the interrupt, (source) → void
    println("status ${sensor.status()}")
    sensor.setMode(Isl29125Full.MODE_POWER_DOWN)                                                    // Power down, (mode) → void
    sensor.softReset()                                                                              // Software reset, () → void
} finally {
    connection.close()
}
