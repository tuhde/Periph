///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-groovy:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.magnetometer.Hmc5883lFull

def connection = new I2CConnection(1, 0x1E)
def hmc5883l = new Hmc5883lFull(connection)

try {
    // --- Identification ---
    def (idA, idB, idC) = hmc5883l.identify()
    printf "ID: 0x%02X 0x%02X 0x%02X\n", idA, idB, idC

    // --- Status ---
    def status = hmc5883l.status()
    printf "Status: 0x%02X\n", status
    def dr = hmc5883l.dataReady()
    println "Data ready: $dr"

    // --- Magnetic field readings ---
    def (x, y, z) = hmc5883l.magneticField()
    printf "X=%.6f T  Y=%.6f T  Z=%.6f T\n", x ?: Double.NaN, y ?: Double.NaN, z ?: Double.NaN

    // --- Configuration ---
    hmc5883l.configure(15, 8, 1)
    hmc5883l.setGain(2)
    hmc5883l.setMode("single")

    // --- Single-shot measurement ---
    Thread.sleep(6)
    def (sx, sy, sz) = hmc5883l.singleMeasurement()
    printf "Single: X=%.6f T  Y=%.6f T  Z=%.6f T\n", sx ?: Double.NaN, sy ?: Double.NaN, sz ?: Double.NaN

    hmc5883l.setMode("continuous")

    // --- Self-test ---
    def (stx, sty, stz) = hmc5883l.selfTest(true)
    printf "Self-test: X=%.6f T  Y=%.6f T  Z=%.6f T\n", stx ?: Double.NaN, sty ?: Double.NaN, stz ?: Double.NaN
} finally {
    connection.close()
}