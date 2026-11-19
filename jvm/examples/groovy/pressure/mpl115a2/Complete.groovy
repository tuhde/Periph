///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-groovy:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.MPL115A2Full

def connection = new I2CConnection(1, 0x60)            // open I²C bus 1, device 0x60, (bus, address=0x60) → I2CConnection
try {
    def sensor = new MPL115A2Full(connection)          // construct driver, loads the coefficients, (connection) → MPL115A2Full

    double[] k = sensor.coefficients()                 // read cached coefficients, () → double[] {a0, b1, b2, c12}
    sensor.reloadCoefficients()                        // re-read coefficient block, () → void
                                                       // use after a power cycle
    double[] both = sensor.read()                      // one conversion, () → double[] {pressure_pa, temperature_c}
    int[] raw = sensor.readRaw()                       // one conversion, () → int[] {padc, tadc} 10-bit counts
    double p = sensor.compensate(raw[0], raw[1])       // compensate raw counts, (padc, tadc) → double Pa
                                                       // pure function, no bus access
    double hpa = sensor.pressureHpa()                  // read pressure, () → double hPa
    double alt = sensor.altitude()                     // compute altitude, (seaLevelPa=101325) → double m
                                                       // barometric formula, informative
    printf("a0=%.3f b1=%.5f b2=%.5f c12=%.8f%n", k[0], k[1], k[2], k[3])
    printf("%.0f Pa  %.1f °C  raw=(%d, %d)  %.0f Pa  %.2f hPa  alt=%.1f m%n",
            both[0], both[1], raw[0], raw[1], p, hpa, alt)
} finally {
    connection.close()
}
