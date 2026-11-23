///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-kotlin:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HDC1080Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = HDC1080Full(connection)                                                         // Create HDC1080 driver, (connection) → void

        sensor.init()                                                                           // Write default configuration, () → void
        println("serial id 0x%010X".format(sensor.serialId()))

        // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
        repeat(15) {
            val w = sensor.read()                                                               // Read temperature and humidity, () → DoubleArray {°C, %RH}
            val g = Math.log(Math.max(w[1], 0.01) / 100.0) + 17.62 * w[0] / (243.12 + w[0])
            val dew = 243.12 * g / (17.62 - g)                                                      // Magnus dew point, °C
            println("%.2f C %.1f %%RH dew point %.2f C".format(w[0], w[1], dew))
            Thread.sleep(2000)
        }

        // --- Heater test: 10 s ---
        val rh0 = sensor.readHumidity()
        val t0 = sensor.readTemperature()
        sensor.setHeater(true)                                                                  // Switch the heater, (on) → void
        Thread.sleep(10000)
        val rh1 = sensor.readHumidity()
        val t1 = sensor.readTemperature()
        println("RH drop %.1f %%RH, temperature rise %.2f K".format(rh0 - rh1, t1 - t0))
        sensor.setHeater(false)

        // --- Resolution comparison ---
        sensor.setResolution(11, 8)                                                             // Set resolution, (temperatureBits, humidityBits) → void
        val ts = System.currentTimeMillis()
        val r = sensor.read()
        println("11/8 bit: %.2f C %.1f %%RH in %d ms".format(r[0], r[1], System.currentTimeMillis() - ts))
        sensor.setResolution(14, 14)
        println("battery low ${sensor.batteryLow()}")
        // sensor.batteryLow() → Boolean, true when VDD is below 2.8 V
    }
}
