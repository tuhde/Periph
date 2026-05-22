///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp280Full

def transport = new I2CTransport(1, 0x76)                // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CTransport
try {
    def sensor = new Bmp280Full(transport)                      // construct driver, verifies chip ID and loads calibration, (transport) → Bmp280Full

    int id = sensor.chipId()                                    // read chip ID register 0xD0, () → int
                                                                // returns 0x58 for BMP280; useful for confirming the device is present
    printf("chip ID: 0x%02X%n", id)

    int st = sensor.status()                                    // read status register 0xF3, () → int
                                                                // bit 3 = measuring, bit 0 = im_update (NVM copy in progress)
    printf("status: 0x%02X%n", st)

    sensor.configure(                                           // configure oversampling, mode, filter, standby, (osrsT, osrsP, mode, filter, tSb) → void
        Bmp280Full.OSRS_X4, Bmp280Full.OSRS_X4,                // temperature ×4, pressure ×4 oversampling
        Bmp280Full.MODE_FORCED,                                 // forced mode: one measurement per call
        Bmp280Full.FILTER_4,                                    // IIR filter coefficient 4
        Bmp280Full.T_SB_0_5_MS)                                 // standby 0.5 ms (only relevant in normal mode)
                                                                // reduces high-frequency noise while retaining step-change response

    double t = sensor.temperature()                             // read temperature, () → double °C
                                                                // triggers forced-mode conversion, applies Bosch 64-bit integer compensation
    printf("temperature: %.2f °C%n", t)

    double p = sensor.pressure()                                // read pressure, () → double hPa
                                                                // re-reads temperature ADC to refresh tFine, then compensates pressure
    printf("pressure: %.2f hPa%n", p)

    double alt = sensor.altitude()                              // compute altitude using default sea-level pressure 1013.25 hPa, () → double m
                                                                // applies barometric formula: 44330 × (1 − (p/1013.25)^(1/5.255))
    printf("altitude: %.1f m%n", alt)

    double altRef = sensor.altitude(1013.00)                   // compute altitude with custom sea-level pressure, (seaLevelHpa=1013.25 hPa) → double m
                                                                // use local QNH for accurate terrain altitude
    printf("altitude (QNH 1013.00): %.1f m%n", altRef)

    double slp = sensor.seaLevelPressure(50.0)                 // back-calculate sea-level pressure at known altitude, (altitudeM m) → double hPa
                                                                // reduces station pressure to MSL using the barometric formula inverse
    printf("sea-level pressure at 50 m: %.2f hPa%n", slp)

    sensor.setOversampling(Bmp280Full.OSRS_X2, Bmp280Full.OSRS_X2) // update oversampling settings, (osrsT, osrsP) → void
                                                                     // preserves current mode bits; reduces measurement time
    sensor.setFilter(Bmp280Full.FILTER_OFF)                    // set IIR filter coefficient, (coeff=0–4) → void
                                                                // FILTER_OFF disables the IIR filter entirely
    sensor.setMode(Bmp280Full.MODE_FORCED)                     // set operating mode, (mode=0/1/3) → void
                                                                // MODE_FORCED: one measurement then sleep; must be re-written each sample
    sensor.setStandby(Bmp280Full.T_SB_125_MS)                  // set standby time (normal mode only), (tSb=0–7) → void
                                                                // T_SB_125_MS: 125 ms between measurements in normal mode

    sensor.reset()                                              // soft-reset the chip and reload calibration, () → void
                                                                // writes 0xB6 to 0xE0, waits 2 ms, re-reads NVM, restores ctrl_meas and config

    printf("temperature after reset: %.2f °C%n", sensor.temperature()) // read temperature, () → double °C
                                                                         // verifies the sensor is functional after reset
} finally {
    transport.close()
}
