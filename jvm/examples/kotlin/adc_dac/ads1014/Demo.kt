///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1014Full

// Shunt-current monitor: a 0.1 ohm shunt in the load supply line is read on
// the ±0.256 V range; the traditional comparator watches for more than 1 A.

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, ADS1014Full.DEFAULT_ADDRESS).use { connection ->   // open I²C bus, device address, (bus, address=0x48) → I2CConnection
        val adc = ADS1014Full(connection)                                               // construct driver, (connection) → ADS1014Full
        val shuntOhms = 0.1

        // --- Pick the range and speed for a small shunt voltage ---
        // ±0.256 V gives 0.125 mV per code; 250 SPS keeps noise low and is plenty
        // for a once-per-second report.
        adc.setGain(ADS1014Full.PGA_0_256)                                              // Set PGA, (pga 0–5) → void
        adc.setDataRate(ADS1014Full.DR_250)                                             // Set data rate, (dr 0–6) → void

        // --- Arm the over-current comparator ---
        // 0.1 V across 0.1 ohm is 1 A. Traditional comparator with hysteresis
        // (clears again below 0.08 V), latched so a brief spike is not missed, and
        // 4 consecutive conversions are needed to assert.
        adc.setThresholds(0.08, 0.1)                                                    // Set both thresholds, (low V, high V) → void
        adc.configureComparator(ADS1014Full.COMP_TRADITIONAL, ADS1014Full.ACTIVE_LOW, true, ADS1014Full.QUEUE_4)  // Configure comparator, (mode, polarity, latch, queue) → void
        adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR)                              // Route comparator to ALERT/RDY, (source) → void

        // --- Monitor the current ---
        // The pin level is not wired in this demo, so the alert state is inferred
        // from the measured current; clearAlert() releases the latch afterwards.
        repeat(20) {
            val v = adc.readVoltage()                                                   // Read voltage, () → double V
            val amps = v / shuntOhms
            println("%.3f V -> %.2f A".format(v, amps))
            if (amps > 1.0) {
                println("over-current!")
                adc.clearAlert()                                                        // Release latched alert, () → void
            }
            Thread.sleep(1000)
        }

        // --- Release the alert pin when finished ---
        adc.disableInterrupt(ADS1014Full.SOURCE_COMPARATOR)                             // Release ALERT/RDY, (source) → void
    }
}
