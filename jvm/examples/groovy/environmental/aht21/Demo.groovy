///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Aht21Full

/**
 * Weather station logger demo: read temperature and humidity every 5 seconds
 * for 60 samples, compute dew point using the Magnus formula, and verify
 * each reading with CRC-8.
 */

final SAMPLES     = 60
final INTERVAL_MS = 5000

def transport = new I2CTransport(1, 0x38)                // open I²C bus 1, device 0x38, (bus, address) → I2CTransport
try {
    def aht = new Aht21Full(transport)                        // construct driver, (transport) → Aht21Full

    // --- Verify calibration before starting the logging session ---
    // Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
    // the driver already sent the calibration init sequence during construction.
    println("Calibrated: ${aht.isCalibrated()}")              // check calibration status, () → boolean

    printf("%-8s %-10s %-10s %-10s%n", 'Time', 'T (°C)', 'RH (%)', 'Dew (°C)')

    (0..<SAMPLES).each { i ->
        // --- Each reading requires an 80 ms measurement cycle ---
        // The sensor cannot output data faster than this; the driver
        // handles the trigger + wait internally.
        double[] rc = aht.readWithCrc()                       // read with CRC verification, () → double[] {temperature_c, humidity_pct, crc_ok}
        if (rc[2] < 0.5d) {
            println("CRC error at sample $i")
            Thread.sleep(INTERVAL_MS)
            return
        }

        double t  = rc[0]
        double rh = rc[1]

        // --- Magnus formula dew-point approximation ---
        // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
        // dew_point = (243.04 * gamma) / (17.625 - gamma)
        // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
        double gamma = Math.log(rh / 100.0d) + (17.625d * t) / (243.04d + t)
        double dew   = (243.04d * gamma) / (17.625d - gamma)

        printf("%-8d %-10.2f %-10.2f %-10.2f%n", i, t, rh, dew)
        Thread.sleep(INTERVAL_MS)
    }
} finally {
    transport.close()
}
