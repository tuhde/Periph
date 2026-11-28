///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Rrh62000Full
import java.util.HexFormat

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x69").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Rrh62000Full(connection)                                              // Create RRH62000 driver, (connection, accessIntervalMs=1000) → Rrh62000Full
        // Starts the start-up timers: I²C after 1 s, valid data after 3.3 s.
        sensor.init()                                                                          // Wait for start-up, check firmware version, () → Unit
        // Reads the firmware version as a presence check.

        println((sensor.firmwareVersion()))                          // Firmware version, () → IntArray {major, minor}
        // Every access is at least 1 s after the previous one (tRD); the driver waits.
        println((sensor.algorithmVersion()))                         // TVOC algorithm version, () → IntArray {major, minor, patch}
        // Version of the ZMOD4410 algorithm in the module.
        println(HexFormat.of().formatHex(sensor.uniqueId()))                        // Unique ID, () → ByteArray (6)
        // Identifies the individual module.

        println(sensor.read())                                                      // Main values, () → Rrh62000Measurement
        // Waits for DATA, reads the 37-byte block, checks the CRC; PM uses the KCl reference.
        println(sensor.readAll())                                                   // Complete block, () → Rrh62000Data
        // Status, 5 number-concentration bins (cm⁻³), PM KCl and smoke, T, RH, TVOC, eCO₂, IAQ.
        println(sensor.readStatus())                                                // Status flags, () → Rrh62000Status {raw, highConcentration, dustAccumulation, fanSpeed, fan}
        // Reads the current block without waiting for new data.
        println(sensor.readParticles())                                             // Particle values, () → Rrh62000Particles
        // PM1/2.5/10 (KCl and cigarette smoke, µg/m³) and the number bins (cm⁻³).
        println(sensor.dataReady())                                                 // New sample available, () → Boolean
        // DATA (0x40); a sample arrives every 3 s.

        sensor.setMovingAverage(20)                                                            // Moving average, (count 1–60) → Unit
        // More averages: smoother values, slower response (24 s at 10).
        println(sensor.getMovingAverage())                                          // Moving average, () → Int
        // 20.
        sensor.setFanSpeed(80)                                                                 // Fan speed, (percent 60–100) → Unit
        // Lower speed is quieter; the default is 86 %.
        println(sensor.getFanSpeed())                                               // Fan speed, () → Int %
        // 80.
        sensor.setCleaningInterval(3 * 24 * 3600)                                              // Cleaning interval, (seconds, multiple of 30) → Unit
        // Written as a 16-bit count of 30 s units, high byte first.
        println(sensor.getCleaningInterval())                                       // Cleaning interval, () → Int s
        // 259200.
        sensor.setCleaningTime(15)                                                             // Cleaning time, (seconds 0–60) → Unit
        // How long the fan runs at full speed during the automatic cleaning.
        println(sensor.getCleaningTime())                                           // Cleaning time, () → Int s
        // 15.

        sensor.startCleaning()                                                                 // Start fan cleaning, () → Unit
        // Blows dust out of the air channel.
        Thread.sleep(15000)
        println(sensor.cleaningDone())                                              // Cleaning status, () → Boolean
        // CSTATUS (0x74): true when completed.
        println(sensor.readMoxResistance())                                         // MOX resistance, () → Long Ω
        // Raw resistance of the TVOC sensing element.

        sensor.sleep()                                                                         // Sleep, () → Unit
        // Stops the fan and the measurement.
        Thread.sleep(5000)
        sensor.wake()                                                                          // Wake up, () → Unit
        // The next access waits 1 s, the next measurement 3.3 s.
        sensor.softReset()                                                                     // Reset, () → Unit
        // Same as power-on: restarts the start-up timers.
        println(sensor.read())                                                      // Main values, () → Rrh62000Measurement
        // First sample after the reset.
    }
}
