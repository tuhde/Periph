///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-kotlin:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.Aht20Full

fun main() {
    I2CConnection(1, 0x38).use { connection ->                 // open I²C bus 1, device 0x38, (bus, address) → I2CConnection

        val aht = Aht20Full(connection)                            // construct driver, (connection) → Aht20Full

        println("Calibrated: ${aht.isCalibrated()}")              // check calibration status, () → Boolean
                                                                  // reads CAL bit from status byte
        println("Busy: ${aht.isBusy()}")                          // check busy status, () → Boolean
                                                                  // reads BUSY bit from status byte

        val (t, h) = aht.read()                                   // trigger measurement, () → Pair<Double °C, Double %RH>
                                                                  // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
        println("T=%.2f °C  H=%.2f %%RH".format(t, h))

        val tr = aht.readTemperature()                            // read temperature only, () → Double °C
                                                                  // triggers full measurement, returns temperature_c
        println("Temperature: %.2f °C".format(tr))

        val hr = aht.readHumidity()                               // read humidity only, () → Double %RH
                                                                  // triggers full measurement, returns humidity_pct
        println("Humidity: %.2f %%RH".format(hr))

        val (rawRh, rawT) = aht.readRaw()                         // read raw 20-bit values, () → Pair<Int, Int> (raw_humidity, raw_temperature)
                                                                  // same measurement as read(), undecoded
        println("raw RH=$rawRh  raw T=$rawT")

        val (_, _, crcOk) = aht.readWithCrc()                     // read with CRC check, () → Triple<Double, Double, Boolean> (temperature_c, humidity_pct, crc_ok)
                                                                  // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
        println("CRC ok: $crcOk")

        println("Status: 0x%02X".format(aht.readStatusByte()))    // read status byte, () → Int
                                                                  // bit 7 BUSY, bit 3 CAL
        println("Dew point: %.2f °C".format(aht.dewPoint()))      // dew point, () → Double °C
                                                                  // one fresh measurement, Magnus formula

        aht.softReset()                                           // send soft reset command, () → Unit
                                                                  // sends 0xBA, waits 20 ms, re-runs the calibration check
    }
}
