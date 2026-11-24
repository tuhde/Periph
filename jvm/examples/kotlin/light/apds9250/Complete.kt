///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9250Minimal
import it.uhde.periph.chips.light.Apds9250Full

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, Apds9250Minimal.DEFAULT_ADDRESS).use { connection ->                        // open I²C bus, device address, (bus, address=0x52) → I2CConnection
        val sensor = Apds9250Full(connection)                                                       // construct driver, (connection) → Apds9250Full
        println("part ${sensor.partId().first} revision ${sensor.partId().second}")                 // Read PART_ID, () → (part, revision) = (0xB, 0x5)
        val c = sensor.read()                                                                       // Read all channels, () → Counts
        println("${c.red} ${c.green} ${c.blue} ${c.ir}")
        println("red ${sensor.readRed()}")                                                          // Read the red channel, () → int
        println("green ${sensor.readGreen()}")                                                      // Read the green / ALS channel, () → int
        println("blue ${sensor.readBlue()}")                                                        // Read the blue channel, () → int
        println("ir ${sensor.readIr()}")                                                            // Read the IR channel, () → int
        println("ready ${sensor.dataReady()}")                                                      // Query LS_DATA, () → boolean (clears the status flags on the chip)
        val block = sensor.readChannels()                                                           // Block-read without waiting, () → Counts

        sensor.setMode(false)                                                                       // Select ALS/IR mode, (colour) → void; false: only green and IR
        sensor.setMode(true)                                                                        // Back to colour mode, (colour) → void
        sensor.disable()                                                                            // Standby, () → void
        sensor.enable()                                                                             // Activate the sensor, () → void

        sensor.setResolution(16)                                                                    // Set the ADC resolution, (bits 13/16/17/18/19/20) → void
        sensor.setMeasurementRate(200)                                                              // Set the measurement rate, (ms 25…2000) → void
        sensor.setGain(6)                                                                           // Set the gain, (1/3/6/9/18) → void; restarts the measurement
        println("resolution ${sensor.getResolution()} bit")                                         // Read the resolution, () → int
        println("rate ${sensor.getMeasurementRate()} ms")                                           // Read the rate, () → int
        println("gain ${sensor.getGain()}")                                                         // Read the gain, () → int
        println("integration ${sensor.integrationTime()} s")                                        // Integration time, () → double seconds
        println("normalized ${sensor.normalized(sensor.readGreen())}")                              // Counts per gain-second, (counts) → double

        sensor.setThresholds(1000, 100000)                                                          // Set the threshold window, (low, high) → void; 20-bit counts
        println("window ${sensor.getThresholds().first} - ${sensor.getThresholds().second}")        // Read the window, () → (low, high)
        sensor.setInterruptChannel(Apds9250Full.CHANNEL_GREEN)                                      // Choose the monitored channel, (channel) → void
        sensor.setPersistence(2)                                                                    // Set the persistence, (1–16) → void
        sensor.setVariance(64)                                                                      // Set the variation threshold, (8…1024, power of two) → void
        sensor.enableInterrupt(Apds9250Full.SOURCE_VARIATION)                                       // Enable the interrupt, (source) → void
        sensor.onInterrupt { s -> println("interrupt $s") }                                         // Subscribe, (callback(status), intPin=null) → void; falling edge or 50 ms poll
        Thread.sleep(10000L)
        println("poll ${sensor.pollInterrupt()}")                                                   // Read and clear MAIN_STATUS, () → int; 0x20 power-on, 0x10 interrupt, 0x08 data
        sensor.offInterrupt()                                                                       // Unsubscribe, () → void
        sensor.disableInterrupt()                                                                   // Disable the interrupt, () → void
        println("status ${sensor.status()}")                                                        // Raw MAIN_STATUS, () → int
        sensor.softReset()                                                                          // Software reset, () → void; all registers to defaults, sensor in standby
    }
}
