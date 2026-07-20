///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp280Full

fun main() {
    I2CTransport(1, 0x76).use { transport ->                 // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CTransport
        val sensor = Bmp280Full(transport)                          // construct driver, verifies chip ID and loads calibration, (transport) → Bmp280Full

        val id = sensor.chipId()                                    // read chip ID register 0xD0, () → Int
                                                                    // returns 0x58 for BMP280; useful for confirming the device is present
        println("chip ID: 0x%02X".format(id))

        val st = sensor.status()                                    // read status register 0xF3, () → Int
                                                                    // bit 3 = measuring, bit 0 = im_update (NVM copy in progress)
        println("status: 0x%02X".format(st))

        sensor.configure(                                           // configure oversampling, mode, filter, standby, (osrsT, osrsP, mode, filter, tSb) → Unit
            Bmp280Full.OSRS_X4, Bmp280Full.OSRS_X4,                // temperature ×4, pressure ×4 oversampling
            Bmp280Full.MODE_FORCED,                                 // forced mode: one measurement per call
            Bmp280Full.FILTER_4,                                    // IIR filter coefficient 4
            Bmp280Full.T_SB_0_5_MS)                                 // standby 0.5 ms (only relevant in normal mode)
                                                                    // reduces high-frequency noise while retaining step-change response

        val t = sensor.temperature()                                // read temperature, () → Double °C
                                                                    // triggers forced-mode conversion, applies Bosch 64-bit integer compensation
        println("temperature: %.2f °C".format(t))

        val p = sensor.pressure()                                   // read pressure, () → Double hPa
                                                                    // re-reads temperature ADC to refresh tFine, then compensates pressure
        println("pressure: %.2f hPa".format(p))

        val alt = sensor.altitude()                                 // compute altitude using default sea-level pressure 1013.25 hPa, () → Double m
                                                                    // applies barometric formula: 44330 × (1 − (p/1013.25)^(1/5.255))
        println("altitude: %.1f m".format(alt))

        val altRef = sensor.altitude(1013.00)                      // compute altitude with custom sea-level pressure, (seaLevelHpa=1013.25 hPa) → Double m
                                                                    // use local QNH for accurate terrain altitude
        println("altitude (QNH 1013.00): %.1f m".format(altRef))

        val slp = sensor.seaLevelPressure(50.0)                    // back-calculate sea-level pressure at known altitude, (altitudeM m) → Double hPa
                                                                    // reduces station pressure to MSL using the barometric formula inverse
        println("sea-level pressure at 50 m: %.2f hPa".format(slp))

        sensor.setOversampling(Bmp280Full.OSRS_X2, Bmp280Full.OSRS_X2) // update oversampling settings, (osrsT, osrsP) → Unit
                                                                         // preserves current mode bits; reduces measurement time
        sensor.setFilter(Bmp280Full.FILTER_OFF)                     // set IIR filter coefficient, (coeff=0–4) → Unit
                                                                     // FILTER_OFF disables the IIR filter entirely
        sensor.setMode(Bmp280Full.MODE_FORCED)                      // set operating mode, (mode=0/1/3) → Unit
                                                                     // MODE_FORCED: one measurement then sleep; must be re-written each sample
        sensor.setStandby(Bmp280Full.T_SB_125_MS)                   // set standby time (normal mode only), (tSb=0–7) → Unit
                                                                     // T_SB_125_MS: 125 ms between measurements in normal mode

        sensor.reset()                                              // soft-reset the chip and reload calibration, () → Unit
                                                                    // writes 0xB6 to 0xE0, waits 2 ms, re-reads NVM, restores ctrl_meas and config

        println("temperature after reset: %.2f °C".format(sensor.temperature())) // read temperature, () → Double °C
                                                                                   // verifies the sensor is functional after reset
    }
}
