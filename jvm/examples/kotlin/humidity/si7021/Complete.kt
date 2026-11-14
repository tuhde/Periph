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

        val v = sensor.read()                                                                       // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // RH without hold master, temperature of the same conversion
        println("%.2f C, %.1f %%RH".format(v[0], v[1]))
        println("humidity %.1f %%RH".format(sensor.readHumidity()))
        // sensor.readHumidity() → double %RH
        println("temperature %.2f C".format(sensor.readTemperature()))
        // sensor.readTemperature() → double °C, new conversion
        println("previous %.2f C".format(sensor.readTemperaturePrevious()))
        // sensor.readTemperaturePrevious() → double °C, command 0xE0, no new conversion
        println("raw %d %d".format(sensor.readRawHumidity(), sensor.readRawTemperature()))
        // sensor.readRawHumidity() / readRawTemperature() → int, 16-bit codes with status bits

        sensor.setResolution(11, 11)                                                                // Set resolution, (rhBits, temperatureBits) → void
                                                                                                    // (12, 14) / (8, 12) / (10, 13) / (11, 11)
        println("resolution %s".format(java.util.Arrays.toString(sensor.getResolution())))
        // sensor.getResolution() → int[] {rhBits, temperatureBits}
        println("conversion time %.4f s".format(sensor.conversionTime()))
        // sensor.conversionTime() → double s, typical RH + temperature time
        sensor.setResolution(12, 14)

        sensor.setHeater(true)                                                                      // Switch the heater, (on) → void
                                                                                                    // about 3.1 mA; readings drift while on
        println("heater on %s".format(sensor.isHeaterOn()))
        // sensor.isHeaterOn() → boolean
        sensor.setHeater(false)
        println("VDD low %s".format(sensor.vddLow()))
        // sensor.vddLow() → boolean, true when VDD is below about 1.9 V
        val reg = sensor.readUserRegister()                                                         // Read USER_REG_1, () → int
                                                                                                    // 0x3A after reset
        sensor.writeUserRegister(reg)                                                               // Write USER_REG_1, (value) → void
                                                                                                    // reserved bits are preserved
        sensor.setClockStretching(false)                                                            // Use hold-master commands, (on) → void
                                                                                                    // needs bus-layer support; off polls the NACKed read header

        println("serial number 0x%016X".format(sensor.serialNumber()))
        // sensor.serialNumber() → long
        println("device type 0x%02X".format(sensor.deviceType()))
        // sensor.deviceType() → int, 0x15 = Si7021
        println("firmware 0x%02X".format(sensor.firmwareRevision()))
        // sensor.firmwareRevision() → int, 0xFF = firmware 1.0
        println("dew point %.2f C".format(sensor.dewPoint()))
        // sensor.dewPoint() → double °C, one measurement, Magnus formula
        sensor.softReset()                                                                          // Software reset, () → void
                                                                                                    // 0xFE, waits 15 ms
    }
}
