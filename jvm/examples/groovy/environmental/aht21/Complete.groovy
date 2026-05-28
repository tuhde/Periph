///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Aht21Full

def transport = new I2CTransport(1, 0x38)                // open I²C bus 1, device 0x38, (bus, address) → I2CTransport
try {
    def aht = new Aht21Full(transport)                        // construct driver, (transport) → Aht21Full

    println("Calibrated: ${aht.isCalibrated()}")              // check calibration status, () → boolean
                                                              // reads CAL bit from status byte
    println("Busy: ${aht.isBusy()}")                          // check busy status, () → boolean
                                                              // reads BUSY bit from status byte

    double[] r = aht.read()                                   // trigger measurement, () → double[] {temperature_c, humidity_pct}
                                                              // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
    printf("T=%.2f °C  H=%.2f %%RH%n", r[0], r[1])

    double t = aht.readTemperature()                          // read temperature only, () → double °C
                                                              // triggers full measurement, returns temperature_c
    printf("Temperature: %.2f °C%n", t)

    double h = aht.readHumidity()                             // read humidity only, () → double %RH
                                                              // triggers full measurement, returns humidity_pct
    printf("Humidity: %.2f %%RH%n", h)

    double[] rc = aht.readWithCrc()                           // read with CRC verification, () → double[] {temperature_c, humidity_pct, crc_ok}
                                                              // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    printf("T=%.2f °C  H=%.2f %%RH  CRC: %s%n", rc[0], rc[1], rc[2] > 0.5d ? 'OK' : 'FAIL')

    aht.softReset()                                           // send soft reset command, () → void
                                                              // sends 0xBA, waits 20 ms for recovery
} finally {
    transport.close()
}
