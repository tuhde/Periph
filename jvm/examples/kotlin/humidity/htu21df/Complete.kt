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
                                                                                                    // 15 ms power-up wait, soft reset, 15 ms
        val v = sensor.read()                                                                       // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // no hold master, CRC checked, one retry on a mismatch
        println("%.2f C, %.1f %%RH".format(v[0], v[1]))
        println("humidity %.1f %%RH".format(sensor.readHumidity()))
        // sensor.readHumidity() → double %RH, not clamped
        println("temperature %.2f C".format(sensor.readTemperature()))
        // sensor.readTemperature() → double °C
        println("compensated humidity %.1f %%RH".format(sensor.readHumidityCompensated()))
        // sensor.readHumidityCompensated() → double %RH, -0.15 %RH/°C relative to 25 °C
        val raw = sensor.readRaw()                                                                  // Read raw codes, () → int[] {temperature, humidity}
                                                                                                    // status bits cleared
        println("raw T %d raw RH %d".format(raw[0], raw[1]))

        sensor.setResolution(3)                                                                     // Set resolution, (res) → void
                                                                                                    // 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
        println("resolution setting %d".format(sensor.getResolution()))
        // sensor.getResolution() → int
        sensor.setResolution(0)

        sensor.setHeater(true)                                                                      // Switch the heater, (on) → void
                                                                                                    // about 5.5 mW; readings drift while on
        println("heater on %s".format(sensor.heater()))
        // sensor.heater() → boolean
        sensor.setHeater(false)
        println("battery low %s".format(sensor.batteryLow()))
        // sensor.batteryLow() → boolean, true when VDD is below about 2.25 V
        val reg = sensor.readUserRegister()                                                         // Read the user register, () → int
                                                                                                    // 0x02 after reset
        println("user register 0x%02X".format(reg))
        sensor.setHoldMaster(false)                                                                 // Use hold-master commands, (on) → void
                                                                                                    // needs bus-layer clock stretching; off polls the NACKed read header
        println("dew point %.2f C".format(sensor.dewPoint()))
        // sensor.dewPoint() → double °C, one measurement
        sensor.softReset()                                                                          // Soft reset, () → void
                                                                                                    // 0xFE, waits 15 ms; the heater bit survives
    }
}
