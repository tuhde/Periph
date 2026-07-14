///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Bme680Full

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toInt(16) ?: 0x76
    I2CTransport(bus, addr).use { transport ->               // open I²C bus, (bus, address=0x76) → I2CTransport
        val sensor = Bme680Full(transport)                          // construct driver, verifies chip ID and loads calibration, (transport) → Bme680Full

        val id = sensor.chipId()                                    // read chip ID register 0xD0, () → Int
                                                                    // returns 0x61 for BME680; useful for confirming the device is present
        println("chip ID: 0x%02X".format(id))

        val st = sensor.status()                                    // read status register 0x1D, () → Int
                                                                    // bit 7 = new_data, bit 6 = gas_measuring, bit 5 = measuring
        println("status: 0x%02X".format(st))

        sensor.configure(                                           // configure oversampling, mode, filter, (osrsT, osrsP, osrsH, mode, filter) → Unit
            Bme680Full.OSRS_X4, Bme680Full.OSRS_X4,                // temperature ×4, pressure ×4 oversampling
            Bme680Full.OSRS_X2,                                     // humidity ×2 oversampling
            Bme680Full.MODE_FORCED,                                 // forced mode: one measurement per call
            Bme680Full.FILTER_3)                                    // IIR filter coefficient 3
                                                                    // reduces high-frequency noise while retaining step-change response

        val t = sensor.temperature()                                // read temperature, () → Double °C
                                                                    // triggers forced-mode TPHG cycle, applies Bosch integer compensation
        println("temperature: %.2f °C".format(t))

        val p = sensor.pressure()                                   // read pressure, () → Double hPa
                                                                    // re-reads temperature ADC to refresh tFine, then compensates pressure
        println("pressure: %.2f hPa".format(p))

        val h = sensor.humidity()                                   // read humidity, () → Double %RH
                                                                    // re-reads temperature ADC to refresh tFine, then compensates humidity
        println("humidity: %.1f %%RH".format(h))

        val g = sensor.gasResistance()                              // read gas resistance, () → Double Ω
                                                                    // computes resistance from gas ADC and range code; NaN if heater unstable
        println("gas resistance: %.0f Ω".format(g))

        val reading = sensor.readAll()                              // read all four values from one TPHG cycle, () → Reading
                                                                    // single cycle is more efficient than four separate calls
        println("readAll: %.2f °C  %.2f hPa  %.1f %%RH  %.0f Ω".format(
            reading.temperatureC, reading.pressureHpa, reading.humidityPct, reading.gasResistanceOhm))

        val gv = sensor.gasValid()                                  // check gas measurement validity, () → Boolean
                                                                    // true if the gas ADC produced a real result (not a dummy slot)
        println("gas valid: $gv")

        val hs = sensor.heaterStable()                              // check heater stability, () → Boolean
                                                                    // true if the heater reached its target temperature within gas_wait
        println("heater stable: $hs")

        sensor.setOversampling(                                     // update oversampling settings, (osrsT, osrsP, osrsH) → Unit
            Bme680Full.OSRS_X2, Bme680Full.OSRS_X2,                // temperature ×2, pressure ×2
            Bme680Full.OSRS_X1)                                     // humidity ×1
                                                                    // preserves current mode bits; reduces measurement time
        sensor.setFilter(Bme680Full.FILTER_0)                      // set IIR filter coefficient, (coeff=0–7) → Unit
                                                                    // FILTER_0 disables the IIR filter entirely

        sensor.setHeater(320, 150)                                  // configure heater profile 0, (tempC=320 °C, durationMs=150 ms) → Unit
                                                                    // sets target temperature and duration, then selects profile 0
        sensor.setHeaterProfile(1, 200, 100)                       // configure heater profile 1, (index, tempC °C, durationMs ms) → Unit
                                                                    // stores profile 1 without activating it
        sensor.selectHeaterProfile(1)                               // activate heater profile 1, (index=0–9) → Unit
                                                                    // subsequent measurements use profile 1's heater settings
        sensor.selectHeaterProfile(0)                               // switch back to profile 0, (index=0–9) → Unit
                                                                    // profile 0 is the default 320 °C / 150 ms configuration

        sensor.setGasEnabled(false)                                 // disable gas conversion, (enabled) → Unit
                                                                    // skips the gas measurement phase to save power and time
        sensor.setGasEnabled(true)                                  // re-enable gas conversion, (enabled) → Unit
                                                                    // restores gas measurement in the forced-mode cycle

        sensor.setHeaterOff(true)                                   // disable heater via heat_off override, (off) → Unit
                                                                    // prevents heater activation regardless of profile settings
        sensor.setHeaterOff(false)                                  // re-enable heater, (off) → Unit
                                                                    // clears the heat_off override bit

        sensor.setAmbientTemperature(25.0)                          // override ambient temperature for heater calc, (tempC °C) → Unit
                                                                    // recomputes heater resistance register using the new ambient value

        sensor.reset()                                              // soft-reset the chip and reload calibration, () → Unit
                                                                    // writes 0xB6 to 0xE0, waits 2 ms, re-reads NVM, restores all registers

        println("temperature after reset: %.2f °C".format(sensor.temperature())) // read temperature, () → Double °C
                                                                                    // verifies the sensor is functional after reset
    }
}
