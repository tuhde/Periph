///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.chips.environmental.Bme280Full
import it.uhde.periph.transport.I2CTransport

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toInt(16) ?: 0x76
    I2CTransport(bus, addr).use { transport ->                  // open I²C bus, (bus, address=0x76) → I2CTransport
        val sensor = Bme280Full(transport)                      // construct driver, verifies chip ID and loads calibration, (transport) → Bme280Full
        val cid = sensor.chipId()                               // read chip ID, () → Int
                                                                     // returns 0x60 for BME280
        sensor.configure(Bme280Full.OSRS_X1, Bme280Full.OSRS_X1, Bme280Full.OSRS_X1, Bme280Full.MODE_SLEEP, Bme280Full.FILTER_OFF, Bme280Full.T_SB_0_5_MS)  // configure chip, (osrsT 0–5, osrsP 0–5, osrsH 0–5, mode 0/1/3, filter 0–4, tSb 0–7) → Unit
                                                                     // writes ctrl_hum, config, ctrl_meas in correct order
        sensor.setOversampling(Bme280Full.OSRS_X4, Bme280Full.OSRS_X2, Bme280Full.OSRS_X1)  // set oversampling, (osrsT 0–5, osrsP 0–5, osrsH 0–5) → Unit
                                                                     // humidity update requires ctrl_meas write to latch
        sensor.setMode(Bme280Full.MODE_FORCED)                  // set power mode, (mode 0/1/3) → Unit
        sensor.setFilter(Bme280Full.FILTER_4)                   // set IIR filter, (coeff 0–4) → Unit
                                                                     // suppresses short-term pressure disturbances
        sensor.setStandby(Bme280Full.T_SB_125_MS)               // set standby time, (tSb 0–7) → Unit
                                                                     // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
        val st = sensor.status()                                 // read status register, () → Int
        val t = sensor.temperature()                             // read temperature, () → Double °C
        val p = sensor.pressure()                                // read pressure, () → Double hPa
        val h = sensor.humidity()                                // read humidity, () → Double %RH
        val alt = sensor.altitude()                              // compute altitude, (seaLevelHpa=1013.25) → Double m
                                                                     // uses barometric formula to convert pressure to metres
        val slp = sensor.seaLevelPressure(alt)                   // compute sea-level pressure, (altitudeM) → Double hPa
        val dp = sensor.dewPoint()                               // compute dew point, () → Double °C
                                                                     // Magnus-Tetens approximation from current T and RH
        sensor.reset()                                           // soft reset chip, () → Unit
                                                                     // re-reads calibration and re-applies configuration
        println("T=%.1f °C, P=%.1f hPa, RH=%.1f %%RH, alt=%.1f m, slp=%.1f hPa, dp=%.1f °C".format(t, p, h, alt, slp, dp))
    }
}
