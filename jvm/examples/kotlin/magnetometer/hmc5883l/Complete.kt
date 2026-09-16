///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.magnetometer.Hmc5883lFull

fun main() {
    I2CConnection(1, 0x1E).use { connection ->
        val hmc5883l = Hmc5883lFull(connection)

        // --- Identification ---
        val (idA, idB, idC) = hmc5883l.identify()
        println("ID: 0x${idA.toString(16).uppercase().padStart(2, '0')} " +
                "0x${idB.toString(16).uppercase().padStart(2, '0')} " +
                "0x${idC.toString(16).uppercase().padStart(2, '0')}")

        // --- Status ---
        val status = hmc5883l.status()
        println("Status: 0x${status.toString(16).uppercase().padStart(2, '0')}")
        val dr = hmc5883l.dataReady()
        println("Data ready: $dr")

        // --- Magnetic field readings ---
        val (x, y, z) = hmc5883l.magneticField()
        println("X=${x?.let { String.format("%.6f", it) } ?: "NaN"} T  " +
                "Y=${y?.let { String.format("%.6f", it) } ?: "NaN"} T  " +
                "Z=${z?.let { String.format("%.6f", it) } ?: "NaN"} T")

        // --- Configuration ---
        hmc5883l.configure(15.0, 8, 1)
        hmc5883l.setGain(2)
        hmc5883l.setMode("single")

        // --- Single-shot measurement ---
        Thread.sleep(6)
        val (sx, sy, sz) = hmc5883l.singleMeasurement()
        println("Single: X=${sx?.let { String.format("%.6f", it) } ?: "NaN"} T  " +
                "Y=${sy?.let { String.format("%.6f", it) } ?: "NaN"} T  " +
                "Z=${sz?.let { String.format("%.6f", it) } ?: "NaN"} T")

        hmc5883l.setMode("continuous")

        // --- Self-test ---
        val (stx, sty, stz) = hmc5883l.selfTest(true)
        println("Self-test: X=${stx?.let { String.format("%.6f", it) } ?: "NaN"} T  " +
                "Y=${sty?.let { String.format("%.6f", it) } ?: "NaN"} T  " +
                "Z=${stz?.let { String.format("%.6f", it) } ?: "NaN"} T")
    }
}