///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HS30xxFull

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = HS30xxFull(connection)                                                         // Create HS30xx driver, (connection) → void
        // Replace the empty body with code that cuts and restores the sensor supply (MOSFET or GPIO).
        val powerCycle: () -> Unit = { }

        val v = sensor.read()                                                                       // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // request, 40 ms wait, 4-byte fetch; one retry on stale data
        println("%.2f C, %.1f %%RH".format(v[0], v[1]))
        val t = sensor.readTemperature()                                                            // Read temperature only, () → double °C
        val rh = sensor.readHumidity()                                                              // Read humidity only, () → double %RH
                                                                                                    // 2-byte fetch
        val raw = sensor.readRaw()                                                                  // Read raw values, () → int[] {humidity, temperature, status}
                                                                                                    // 14-bit values; status 0 = valid, 1 = stale
        println("raw RH %d T %d status %d".format(raw[0], raw[1], raw[2]))
        sensor.setMeasurementWait(60)                                                               // Override the post-request wait, (ms) → void
                                                                                                    // default 40 ms; lower resolutions need less
        val dew = sensor.dewPoint()                                                                 // Compute dew point, () → double °C
                                                                                                    // one measurement, Magnus formula
        println("dew point %.2f C".format(dew))

        // NVM access only works within 10 ms after power-up, so it needs the powerCycle helper above.
        val res = sensor.getResolution(powerCycle)                                                  // Get NVM resolution, (powerCycle) → int[] {humidity bits, temperature bits}
        println("resolution %d/%d bit".format(res[0], res[1]))
        sensor.setResolution(12, 12, powerCycle)                                                    // Set NVM resolution, (humidityBits, temperatureBits, powerCycle) → void
                                                                                                    // 8, 10, 12 or 14 bit; null leaves a channel unchanged
        val id = sensor.readId(powerCycle)                                                          // Read the 32-bit sensor ID, (powerCycle) → long
        println("id 0x%08X".format(id))
        sensor.enterProgrammingMode()                                                               // Enter programming mode, () → void
                                                                                                    // only within 10 ms after power-up
        val reg = sensor.readNvm(0x06)                                                              // Read an NVM register, (reg) → int
                                                                                                    // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
        println("NVM 0x06 = 0x%04X".format(reg))
        sensor.exitProgrammingMode()                                                                // Leave programming mode, () → void
    }
}
