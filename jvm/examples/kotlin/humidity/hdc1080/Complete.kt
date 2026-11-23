///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HDC1080Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = HDC1080Full(connection)                                                         // Create HDC1080 driver, (connection) → void

        sensor.init()                                                                           // Write default configuration, () → void
        println("serial id 0x%010X".format(sensor.serialId()))                                  // Read the 40-bit serial ID, () → Long
        println("config 0x%04X".format(sensor.readConfig()))                                    // Read the configuration register, () → Int
        val v = sensor.read()                                                                   // Read temperature and humidity, () → DoubleArray {°C, %RH}
        println("%.2f C, %.1f %%RH".format(v[0], v[1]))
        println("temperature %.2f C".format(sensor.readTemperature()))
        // sensor.readTemperature() → Double °C, single mode
        println("humidity %.1f %%RH".format(sensor.readHumidity()))
        // sensor.readHumidity() → Double %RH, single mode
        val raw = sensor.readRaw()                                                              // Read raw words, () → IntArray {temperature, humidity}
        println("raw T ${raw[0]} raw RH ${raw[1]}")

        sensor.setResolution(11, 8)                                                             // Set resolution, (temperatureBits, humidityBits) → void
        println("conversion time %.2f ms".format(sensor.conversionTime() * 1000.0))
        // sensor.conversionTime() → Double s, typical T + RH time
        sensor.setResolution(14, 14)

        sensor.setHeater(true)                                                                  // Switch the heater, (on) → void
        println("heater on ${sensor.isHeaterOn()}")
        // sensor.isHeaterOn() → Boolean
        sensor.setHeater(false)
        println("battery low ${sensor.batteryLow()}")
        // sensor.batteryLow() → Boolean, true when VDD is below 2.8 V
        sensor.softwareReset()                                                                  // Software reset, () → void

        println("manufacturer 0x%04X".format(sensor.manufacturerId()))                          // Manufacturer ID (0x5449), () → Int
        println("device 0x%04X".format(sensor.deviceId()))                                      // Device ID (0x1050), () → Int
    }
}
