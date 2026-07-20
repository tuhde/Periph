///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp180Full

fun main() {
    I2CTransport(1, 0x77).use { transport ->                 // open I²C bus 1, device 0x77, (bus, address=0x77) → I2CTransport
        val sensor = Bmp180Full(transport)                          // construct driver, verifies chip ID and loads calibration, (transport) → Bmp180Full

        val id = sensor.chipId()                                    // read chip ID register 0xD0, () → Int
                                                                    // returns 0x55 for BMP180; useful for confirming the device is present
        println("chip ID: 0x%02X".format(id))

        val oss = sensor.oversampling()                             // read current OSS setting, () → Int
                                                                    // 0 = ultra-low-power (default), 1 = standard, 2 = high-res, 3 = ultra-high-res
        println("oversampling: $oss")

        sensor.setOversampling(Bmp180Full.OSS_HIGH_RES)            // set oversampling to high-resolution mode, (oss=0–3) → Unit
                                                                    // OSS = 2: 4 internal samples averaged, ~13.5 ms conversion time

        val t = sensor.temperature()                                // read temperature, () → Double °C
                                                                    // triggers a 5 ms ADC conversion, applies Bosch integer compensation
        println("temperature: %.2f °C".format(t))

        val p = sensor.pressure()                                   // read pressure, () → Double hPa
                                                                    // re-reads temperature to refresh B5, then triggers pressure ADC
        println("pressure: %.2f hPa".format(p))

        val alt = sensor.altitude()                                 // compute altitude using default sea-level pressure 1013.25 hPa, () → Double m
                                                                    // applies barometric formula: 44330 × (1 − (p/1013.25)^(1/5.255))
        println("altitude: %.1f m".format(alt))

        val altRef = sensor.altitude(1013.00)                      // compute altitude with custom sea-level pressure, (seaLevelHpa=1013.25 hPa) → Double m
                                                                    // use local QNH for accurate terrain altitude
        println("altitude (QNH 1013.00): %.1f m".format(altRef))

        val slp = sensor.seaLevelPressure(50.0)                    // back-calculate sea-level pressure at known altitude, (altitudeM=0.0 m) → Double hPa
                                                                    // reduces station pressure to MSL using the barometric formula inverse
        println("sea-level pressure at 50 m: %.2f hPa".format(slp))

        sensor.reset()                                              // soft-reset the chip and reload calibration, () → Unit
                                                                    // writes 0xB6 to 0xE0, waits 15 ms, then re-reads calibration EEPROM

        sensor.setOversampling(Bmp180Full.OSS_ULP)                 // restore ultra-low-power mode, (oss=0–3) → Unit
                                                                    // OSS = 0: single sample, ~4.5 ms, lowest power consumption

        println("oversampling after reset: ${sensor.oversampling()}") // read current OSS setting, () → Int
                                                                       // reset() restores chip but local field retains 0 since it was set after construction
    }

}
