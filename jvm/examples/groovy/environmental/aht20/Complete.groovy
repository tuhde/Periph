///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.Aht20Full

def connection = new I2CConnection(1, 0x38)                // open I²C bus 1, device 0x38, (bus, address) → I2CConnection
try {
    def aht = new Aht20Full(connection)                        // construct driver, (connection) → Aht20Full

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

    int[] raw = aht.readRaw()                                 // read raw 20-bit values, () → int[] {raw_humidity, raw_temperature}
                                                              // same measurement as read(), undecoded
    printf("raw RH=%d  raw T=%d%n", raw[0], raw[1])

    double[] withCrc = aht.readWithCrc()                      // read with CRC check, () → double[] {temperature_c, humidity_pct, crc_ok (1.0/0.0)}
                                                              // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    printf("CRC ok: %b%n", withCrc[2] == 1.0d)

    printf("Status: 0x%02X%n", aht.readStatusByte())          // read status byte, () → int
                                                              // bit 7 BUSY, bit 3 CAL
    printf("Dew point: %.2f °C%n", aht.dewPoint())            // dew point, () → double °C
                                                              // one fresh measurement, Magnus formula

    aht.softReset()                                           // send soft reset command, () → void
                                                              // sends 0xBA, waits 20 ms, re-runs the calibration check
} finally {
    connection.close()
}
