///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4728Full

/**
 * Four-point calibration and synchronous staircase: drives the four channels
 * of an MCP4728 quad 12-bit DAC through a calibration sequence and a
 * lock-step staircase, printing the target fraction and approximate voltage
 * for each step (assumes 3.3 V supply).
 */

private const val VDD     = 3.3
private const val STEP    = 1.0 / 16.0
private const val STEP_MS = 50L

fun main() {
    I2CTransport(1, 0x60).use { transport ->         // open I²C bus 1, device 0x60, (bus, address) → I2CTransport
        val dac = Mcp4728Full(transport, null)              // construct driver (no general call needed for this demo), (transport, generalCall) → Mcp4728Full

        // --- Apply four-point calibration voltages to channels A–D ---
        // A 4-channel DAC is the canonical way to bias a 4-point sensor bridge
        // (load cell, RTD conditioning, strain gauge). Each channel gets a
        // different fraction of full scale to demonstrate independent outputs.
        // Voltages printed below assume a 3.3 V supply.
        val calibration = doubleArrayOf(0.0, 1.0 / 3, 2.0 / 3, 1.0)
        dac.setAll(calibration)                              // update all four channels simultaneously, (fractions[4]) → Unit
        for (ch in 0..3) {
            val code = (calibration[ch] * 4095).toInt()
            println("ch=$ch fraction=%.4f code=%4d approx_v=%.3fV".format(calibration[ch], code, code * VDD / 4096))
        }
        Thread.sleep(500)

        // --- Synchronous staircase from 0 to full scale on all four channels ---
        // Using setAll with the same fraction across channels keeps them in lock-step
        // and demonstrates simultaneous V_OUT update via Fast Write. A 50 ms pause
        // between steps lets the host controller observe each level on the scope.
        for (n in 0..16) {
            val f = n * STEP
            dac.setAll(doubleArrayOf(f, f, f, f))            // update all four channels simultaneously, (fractions[4]) → Unit
            val code = (f * 4095).toInt()
            println("step=%2d fraction=%.4f code=%4d approx_v=%.3fV".format(n, f, code, code * VDD / 4096))
            Thread.sleep(STEP_MS)
        }

        // --- Reset all channels to 0 V before exit ---
        // Avoids leaving the rail at an arbitrary level when the process ends.
        dac.setAll(doubleArrayOf(0.0, 0.0, 0.0, 0.0))        // update all four channels simultaneously, (fractions[4]) → Unit
    }
}
