///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HTU21DFFull

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = HTU21DFFull(connection)                                                         // Create HTU21D-F driver, (connection) → void

        sensor.init()                                                                               // Soft reset, () → void
        sensor.softReset()                                                                          // Soft reset, () → void
        println("user register 0x%02X, resolution %d".format(sensor.readUserRegister(), sensor.getResolution()))
        // sensor.readUserRegister() → int ; sensor.getResolution() → int

        // --- Five readings at 12/14 bit ---
        for (i in 0 until 5) {
            val w = sensor.read()                                                                   // Read temperature and humidity, () → double[] {°C, %RH}
            println("%.2f C %.1f %%RH (compensated %.1f) dew point %.2f C".format(w[0], w[1], sensor.readHumidityCompensated(), sensor.dewPoint()))
            // sensor.readHumidityCompensated() → double %RH ; sensor.dewPoint() → double °C
            Thread.sleep(2000)
        }
        val raw = sensor.readRaw()                                                                  // Read raw codes, () → int[] {temperature, humidity}
        println("raw T %d raw RH %d".format(raw[0], raw[1]))

        // --- 8/12 bit: shorter conversion, coarser resolution ---
        sensor.setResolution(1)                                                                     // Set resolution, (res) → void
        val t0 = System.currentTimeMillis()
        val t812 = sensor.readTemperature()
        val rh812 = sensor.readHumidity()
        println("8/12 bit: %.2f C %.1f %%RH in %d ms".format(t812, rh812, System.currentTimeMillis() - t0))
        sensor.setResolution(0)
        sensor.setHoldMaster(false)                                                                 // Use hold-master commands, (on) → void

        // --- Heater self-check: 10 s ---
        val rhBefore = sensor.readHumidity()
        val tBefore = sensor.readTemperature()
        sensor.setHeater(true)                                                                      // Switch the heater, (on) → void
        Thread.sleep(10000)
        println("heater on %s".format(sensor.heater()))
        // sensor.heater() → boolean
        println("RH drop %.1f %%RH, temperature rise %.2f K".format(rhBefore - sensor.readHumidity(), sensor.readTemperature() - tBefore))
        sensor.setHeater(false)
        println("battery low %s".format(sensor.batteryLow()))
        // sensor.batteryLow() → boolean
    }
}
