///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4725Full

/**
 * Triangle wave: ramp the DAC from 0 to full scale and back in 1/20 steps,
 * printing the fraction and approximate voltage at each step (assumes 3.3 V supply).
 * Makes a visible sawtooth on an oscilloscope and demonstrates 12-bit resolution.
 */

private const val VDD     = 3.3
private const val STEPS   = 20
private const val STEP_MS = 100L

fun main() {
    val pi4j = Pi4J.newAutoContext()                              // initialise Pi4J, () → Context
    try {
        I2CTransport(pi4j, 1, 0x60).use { transport ->           // open I²C bus 1, device 0x60, (bus, address) → I2CTransport
            val dac = Mcp4725Full(transport, null)                // construct driver (no general call needed for this demo), (transport, generalCall) → Mcp4725Full

            // --- Ramp up from 0 V to VDD ---
            // Each step covers 1/20 of full scale (~165 mV on a 3.3 V rail).
            // 100 ms per step gives a 2-second rise time — slow enough to observe
            // on a multimeter and fast enough to show a clean ramp on an oscilloscope.
            for (i in 0..STEPS) {
                val fraction = i.toDouble() / STEPS
                dac.setVoltage(fraction)                          // set output level, (fraction=0.0–1.0) → Unit
                println("↑ fraction=%.2f  V≈%.3f V".format(fraction, fraction * VDD))
                Thread.sleep(STEP_MS)
            }

            // --- Ramp down from VDD back to 0 V ---
            // Descending half of the triangle wave.
            for (i in STEPS - 1 downTo 0) {
                val fraction = i.toDouble() / STEPS
                dac.setVoltage(fraction)                          // set output level, (fraction=0.0–1.0) → Unit
                println("↓ fraction=%.2f  V≈%.3f V".format(fraction, fraction * VDD))
                Thread.sleep(STEP_MS)
            }

            // --- Return output to 0 V before exit ---
            // Avoids leaving the rail at an arbitrary level when the process ends.
            dac.setRaw(0)                                         // set output to 0 V, (code=0–4095) → Unit
        }
    } finally {
        pi4j.shutdown()
    }
}
