///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HS40xxFull

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x54").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = HS40xxFull(connection)                                                         // Create HS40xx driver, (connection) → void

        val v = sensor.read()                                                                       // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
        println("%.2f C, %.1f %%RH".format(v[0], v[1]))
        val t = sensor.readTemperature()                                                            // Read temperature only, () → double °C
                                                                                                    // no-hold 0xF3, 3 bytes
        val rh = sensor.readHumidity()                                                              // Read humidity only, () → double %RH
        val hold = sensor.readHold()                                                                // Read with clock stretching, () → double[] {°C, %RH}
                                                                                                    // 0xE5; needs a stretching controller and SCL >= 200 kHz
        val raw = sensor.readRaw()                                                                  // Read raw values, () → int[] {humidity, temperature}
                                                                                                    // 14-bit values, CRC verified
        println("raw RH ${raw[0]} T ${raw[1]}")
        sensor.setResolution(12, 12)                                                                // Set resolution, (humidityBits, temperatureBits) → void
                                                                                                    // 8, 10, 12 or 14 bit; null leaves a channel unchanged
        val res = sensor.getResolution()                                                            // Get resolution, () → int[] {humidity bits, temperature bits}
        println("resolution ${res[0]}/${res[1]} bit")
        val reg = sensor.readRegister(0x00)                                                         // Read a register, (reg) → int
        sensor.writeRegister(0x00, 0x0F)                                                            // Write a register, (reg, value) → void
                                                                                                    // 0x0F = 14 bit for both channels (default)
        sensor.setAlertThresholds(rhHigh = 60.0, rhHighReset = 55.0, tempHigh = 40.0, tempHighReset = 38.0)// Set alert thresholds, (rhHigh, rhHighReset, rhLow, rhLowReset, tempHigh, tempHighReset, tempLow, tempLowReset) → void
                                                                                                    // %RH / °C; null leaves a threshold unchanged
        sensor.setAlertPolarity(true)                                                               // Set ALERT polarity, (activeHigh) → void
        sensor.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH or HS40xxFull.SOURCE_TEMP_HIGH)            // Enable alert sources, (source) → void
        sensor.startPeriodic(1.0)                                                                   // Start periodic mode, (periodS) → void
                                                                                                    // 0.5, 1 or 2.5 s
        Thread.sleep(1500)
        val p = sensor.readPeriodic()                                                               // Fetch the latest periodic result, () → double[] {°C, %RH, status}
        println("periodic %.2f C %.1f %%RH status %d".format(p[0], p[1], p[2].toInt()))
        val status = sensor.pollInterrupt()                                                         // Read alert status, () → int
                                                                                                    // SOURCE_* bitmask
        sensor.onInterrupt({ println("alert $it") })                                                // Subscribe to the ALERT pin, (callback) → void
                                                                                                    // polls at the periodic interval without a pin
        Thread.sleep(5000)
        sensor.offInterrupt()                                                                       // Unsubscribe, () → void
        sensor.disableInterrupt(HS40xxFull.SOURCE_RH_HIGH or HS40xxFull.SOURCE_TEMP_HIGH)           // Disable alert sources, (source) → void
        sensor.stopPeriodic()                                                                       // Stop periodic mode, () → void
        val id = sensor.readId()                                                                    // Read the 32-bit sensor ID, () → long
        println("id 0x%08X".format(id))
        val dew = sensor.dewPoint()                                                                 // Compute dew point, () → double °C
                                                                                                    // one measurement, Magnus formula
        println("dew point %.2f C".format(dew))
        sensor.softReset()                                                                          // Soft reset, () → void
                                                                                                    // stops periodic mode, waits 500 ms
    }
}
