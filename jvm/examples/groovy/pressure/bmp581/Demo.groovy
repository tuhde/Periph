///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp581Full

def connection = new I2CConnection(1, 0x46)                      // open I²C bus 1, device 0x46, (bus, address=0x46) → I2CConnection
try {
    // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
    def bmp = new Bmp581Full(connection)                             // construct driver, (connection) → Bmp581Full
    bmp.configure(0x17, Bmp581Full.OSR_16X, Bmp581Full.OSR_4X, true) // configure, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → void

    def pressures = new double[300]
    def temps = new double[300]
    def alts = new double[300]
    300.times { n ->
        pressures[n] = bmp.pressure()                                // read pressure, () → double Pa
        temps[n] = bmp.temperature()                                  // read temperature, () → double °C
        alts[n] = bmp.altitude()                                      // compute altitude, (seaLevelPa=101325.0) → double
        if (n % 10 == 0) {
            def start = Math.max(0, n - 10)
            def span = Math.min(10, n)
            if (span > 0) {
                def mp = (start..<n).sum { pressures[it] } / span
                def mt = (start..<n).sum { temps[it] } / span
                def ma = (start..<n).sum { alts[it] } / span
                printf("%d0s: rolling P=%.1f Pa  T=%.2f C  alt=%.2f m%n", n.intdiv(10), mp, mt, ma)
            }
        }
        Thread.sleep(100)
    }
    def amin = alts.min(); def amax = alts.max()
    printf("Bypass: alt min=%.3f max=%.3f spread=%.3f m%n", amin, amax, amax - amin)

    bmp.setIirFilter(Bmp581Full.IIR_COEFF_3, Bmp581Full.IIR_BYPASS)  // set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → void

    def alts2 = new double[300]
    300.times { n ->
        bmp.pressure()                                                // read pressure, () → double Pa
        alts2[n] = bmp.altitude()                                      // compute altitude, (seaLevelPa=101325.0) → double
        Thread.sleep(100)
    }
    def amin2 = alts2.min(); def amax2 = alts2.max()
    printf("IIR=3:  alt min=%.3f max=%.3f spread=%.3f m%n", amin2, amax2, amax2 - amin2)

    def pmin = pressures.min(); def pmax = pressures.max()
    def psum = pressures.sum()
    printf("Min P=%.1f, max P=%.1f, mean P=%.1f Pa%n", pmin, pmax, psum / pressures.length)
} finally {
    connection.close()
}