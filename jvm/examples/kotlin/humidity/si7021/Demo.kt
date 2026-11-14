///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.Si7021Full

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Si7021Full(connection)                                                         // Create Si7021 driver, (connection) → void

        sensor.softReset()                                                                          // Software reset, () → void
        println("device type 0x%02X".format(sensor.deviceType()))
        // sensor.deviceType() → int
        println("serial number 0x%016X".format(sensor.serialNumber()))
        // sensor.serialNumber() → long
        println("firmware 0x%02X".format(sensor.firmwareRevision()))
        // sensor.firmwareRevision() → int
        println("VDD low %s".format(sensor.vddLow()))
        // sensor.vddLow() → boolean

        // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
        repeat(15) {
            val v = sensor.read()                                                                   // Read temperature and humidity, () → double[] {°C, %RH}
            println("%.2f C %.1f %%RH dew point %.2f C".format(v[0], v[1], sensor.dewPoint()))
            // sensor.dewPoint() → double °C
            Thread.sleep(2000)
        }

        // --- Value accessors and raw codes ---
        println("RH %.1f T %.2f T of last RH %.2f".format(sensor.readHumidity(), sensor.readTemperature(), sensor.readTemperaturePrevious()))
        println("raw %d %d".format(sensor.readRawHumidity(), sensor.readRawTemperature()))
        sensor.setClockStretching(false)                                                            // Use hold-master commands, (on) → void
        val reg = sensor.readUserRegister()                                                         // Read USER_REG_1, () → int
        sensor.writeUserRegister(reg)                                                               // Write USER_REG_1, (value) → void

        // --- Resolution comparison ---
        for (p in listOf(intArrayOf(12, 14), intArrayOf(10, 13), intArrayOf(11, 11), intArrayOf(8, 12))) {
            sensor.setResolution(p[0], p[1])                                                        // Set resolution, (rhBits, temperatureBits) → void
            val t0 = System.nanoTime()
            val v = sensor.read()
            val ms = (System.nanoTime() - t0) / 1e6
            println("%d/%d bit: %.2f C %.1f %%RH, %.1f ms (typ. %.1f ms), resolution %s".format(p[0], p[1], v[0], v[1], ms, sensor.conversionTime() * 1000, java.util.Arrays.toString(sensor.getResolution())))
            // sensor.conversionTime() → double s ; sensor.getResolution() → int[]
        }
        sensor.setResolution(12, 14)

        // --- Heater test: 10 s ---
        val rhBefore = sensor.readHumidity()
        val tBefore = sensor.readTemperature()
        sensor.setHeater(true)                                                                      // Switch the heater, (on) → void
        Thread.sleep(10000)
        println("heater on %s".format(sensor.isHeaterOn()))
        // sensor.isHeaterOn() → boolean
        println("RH drop %.1f %%RH, temperature rise %.2f K".format(rhBefore - sensor.readHumidity(), sensor.readTemperature() - tBefore))
        sensor.setHeater(false)
    }
}
