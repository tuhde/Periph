///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Aht21Full
import kotlin.math.ln

/**
 * Weather station logger demo: read temperature and humidity every 5 seconds
 * for 60 samples, compute dew point using the Magnus formula, and verify
 * each reading with CRC-8.
 */

private const val SAMPLES     = 60
private const val INTERVAL_MS = 5000L

fun main() {
    I2CTransport(1, 0x38).use { transport ->                 // open I²C bus 1, device 0x38, (bus, address) → I2CTransport
        val aht = Aht21Full(transport)                            // construct driver, (transport) → Aht21Full

        // --- Verify calibration before starting the logging session ---
        // Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
        // the driver already sent the calibration init sequence during construction.
        println("Calibrated: ${aht.isCalibrated()}")              // check calibration status, () → Boolean

        println("%-8s %-10s %-10s %-10s".format("Time", "T (°C)", "RH (%)", "Dew (°C)"))

        for (i in 0 until SAMPLES) {
            // --- Each reading requires an 80 ms measurement cycle ---
            // The sensor cannot output data faster than this; the driver
            // handles the trigger + wait internally.
            val (t, h, crcOk) = aht.readWithCrc()                 // read with CRC verification, () → Triple<Double °C, Double %RH, Boolean>
            if (!crcOk) {
                println("CRC error at sample $i")
                Thread.sleep(INTERVAL_MS)
                continue
            }

            // --- Magnus formula dew-point approximation ---
            // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
            // dew_point = (243.04 * gamma) / (17.625 - gamma)
            // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
            val gamma = ln(h / 100.0) + (17.625 * t) / (243.04 + t)
            val dew   = (243.04 * gamma) / (17.625 - gamma)

            println("%-8d %-10.2f %-10.2f %-10.2f".format(i, t, h, dew))
            Thread.sleep(INTERVAL_MS)
        }
    }
}
