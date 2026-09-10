///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps33hwFull

/**
 * Altimeter scenario: read pressure and temperature at 10 Hz and compute
 * altitude above sea level using the barometric formula. Every 10 seconds,
 * AUTOZERO re-zeros the sensor to the current ambient pressure.
 */
final int    SAMPLES     = 60
final long   INTERVAL_MS = 1000
final double SEA_LEVEL_PA = 101325.0

def connection = new I2CConnection(1, 0x5C)             // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
try {
    def sensor = new Lps33hwFull(connection)                  // construct driver, verifies chip ID, (connection) → Lps33hwFull

    // --- Initialization and configuration ---
    // 10 Hz ODR + BDU=1 + EN_LPFP=1/LPFP_CFG=1 (ODR/20) gives smooth
    // pressure readings. The barometric formula needs pressure ratios,
    // so reducing short-term noise is the dominant accuracy lever.
    sensor.configure(                                       // configure CTRL_REG1+RES_CONF, (odr=10 Hz, bdu=true, enLpfp=true, lpfpCfg=ODR/20, lcEn=false, sim=false) → void
        Lps33hwFull.ODR_10_HZ, true, true,
        Lps33hwFull.LPFP_BW_ODR_20, false, false)
    sensor.resetLpf()                                       // reset LPF, () → void
                                                             // flushes transitory state after enabling EN_LPFP

    // --- Main loop ---
    // Pressure is polled on P_DA rather than by fixed delay so we
    // read the freshest possible sample every cycle.
    for (int n = 0; n < SAMPLES; n++) {
        double t = sensor.temperature()                       // read temperature, () → double °C
        double p = sensor.pressure()                          // read pressure, () → double Pa

        // --- Altitude calculation ---
        // Barometric formula: altitude_m = 44330 × (1 − (P/P0)^(1/5.255)).
        // Valid for troposphere below ~11 km; absolute altitude depends
        // on local sea-level reference, but relative changes (e.g.
        // drone altitude tracking) are accurate.
        double ratio = p / SEA_LEVEL_PA
        double altitude_m = 44330.0 * (1.0 - Math.pow(ratio, 1.0 / 5.255))
        printf("[%2d] temperature=%.2f °C  pressure=%.1f Pa  altitude=%.1f m%n",
            n + 1, t, p, altitude_m)

        // --- Autozero every 10 seconds ---
        // Re-zeroing removes slow atmospheric pressure drift for
        // relative altitude measurements.
        if (n > 0 && (n + 1) % 10 == 0) {
            sensor.setAutozero()                              // set AUTOZERO, () → void
            println("Reference updated.")
        }

        Thread.sleep(INTERVAL_MS)
    }
} finally {
    connection.close()
}