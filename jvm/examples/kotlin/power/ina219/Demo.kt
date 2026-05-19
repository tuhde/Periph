///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina219Full

/**
 * Power-supply sanity check: poll the INA219 once per second for 10 seconds,
 * printing bus voltage, current, and power. A prompt between samples 4 and 5
 * lets the user switch on the load mid-run. After the run, min/max/mean of
 * each measured quantity are reported.
 */

private const val SAMPLES   = 10
private const val PERIOD_MS = 1000L

fun main() {
    val pi4j = Pi4J.newAutoContext()                                   // initialise Pi4J, () → Context
    try {
        I2CTransport(pi4j, 1, 0x40).use { transport ->                // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
            val ina = Ina219Full(transport, 0.1, 2.0)                  // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina219Full

            // --- Configure for noise-sensitive power rail monitoring ---
            // 128-sample averaging suppresses switching noise on a noisy 5 V rail;
            // continuous mode avoids re-triggering overhead between measurements.
            ina.configure(Ina219Full.BRNG_32V, Ina219Full.PGA_8,
                          Ina219Full.ADC_AVG_128, Ina219Full.ADC_AVG_128,
                          Ina219Full.MODE_SHUNT_BUS_CONT)              // configure ADC, (brng, pga, badc, sadc, mode) → Unit

            val voltages = DoubleArray(SAMPLES)
            val currents = DoubleArray(SAMPLES)
            val powers   = DoubleArray(SAMPLES)

            println("=== INA219 power-supply sanity check ===")
            println("%-4s  %-10s  %-10s  %-10s".format("#", "V_bus (V)", "I (A)", "P (W)"))

            // --- Collect 10 one-second samples ---
            // Samples 1–4 are taken with the load in its initial state.
            // After sample 4 the user is prompted to switch the load on,
            // making the current step visible in the data.
            for (n in 0 until SAMPLES) {
                if (n == 4) {
                    println("Switch on load now...")
                }

                val v = ina.voltage()   // read bus voltage, () → Double V
                val i = ina.current()   // read current, () → Double A
                val p = ina.power()     // read power, () → Double W

                voltages[n] = v
                currents[n] = i
                powers[n]   = p

                println("%-4d  %-10.3f  %-10.4f  %-10.4f".format(n + 1, v, i, p))
                Thread.sleep(PERIOD_MS)
            }

            // --- Compute and print summary statistics ---
            // Min/max/mean give a quick overview of load stability and
            // reveal any voltage sag under load.
            println("\n=== Summary ===")
            println("%-12s  %-10s  %-10s  %-10s".format("", "V_bus (V)", "I (A)", "P (W)"))
            println("%-12s  %-10.3f  %-10.4f  %-10.4f".format(
                "min", voltages.min(), currents.min(), powers.min()))
            println("%-12s  %-10.3f  %-10.4f  %-10.4f".format(
                "max", voltages.max(), currents.max(), powers.max()))
            println("%-12s  %-10.3f  %-10.4f  %-10.4f".format(
                "mean", voltages.average(), currents.average(), powers.average()))
        }
    } finally {
        pi4j.shutdown()
    }
}
