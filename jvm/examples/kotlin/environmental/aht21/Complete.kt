///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Aht21Full

fun main() {
    I2CTransport(1, 0x38).use { transport ->                 // open I²C bus 1, device 0x38, (bus, address) → I2CTransport

        val aht = Aht21Full(transport)                            // construct driver, (transport) → Aht21Full

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

        val (tc, hc, crcOk) = aht.readWithCrc()                   // read with CRC verification, () → Triple<Double °C, Double %RH, Boolean>
                                                                  // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
        println("T=%.2f °C  H=%.2f %%RH  CRC: %s".format(tc, hc, if (crcOk) "OK" else "FAIL"))

        aht.softReset()                                           // send soft reset command, () → Unit
                                                                  // sends 0xBA, waits 20 ms for recovery
    }
}
