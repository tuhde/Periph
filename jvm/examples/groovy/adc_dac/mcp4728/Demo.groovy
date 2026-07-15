///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4728Full

/**
 * Four-point calibration and synchronous staircase: drives the four channels
 * of an MCP4728 quad 12-bit DAC through a calibration sequence and a
 * lock-step staircase, printing the target fraction and approximate voltage
 * for each step (assumes 3.3 V supply).
 */

final VDD     = 3.3d
final STEP    = 1.0d / 16.0d
final STEP_MS = 50L

def transport = new I2CTransport(1, 0x60)        // open I²C bus 1, device 0x60, (bus, address) → I2CTransport
try {
    def dac = new Mcp4728Full(transport, null)            // construct driver (no general call needed for this demo), (transport, generalCall) → Mcp4728Full

    // --- Apply four-point calibration voltages to channels A–D ---
    // A 4-channel DAC is the canonical way to bias a 4-point sensor bridge
    // (load cell, RTD conditioning, strain gauge). Each channel gets a
    // different fraction of full scale to demonstrate independent outputs.
    // Voltages printed below assume a 3.3 V supply.
    def calibration = [0.0d, 1.0d / 3.0d, 2.0d / 3.0d, 1.0d] as double[]
    dac.setAll(calibration)                            // update all four channels simultaneously, (fractions[4]) → void
    (0..3).each { ch ->
        double f = calibration[ch]
        int code = (int) Math.round(f * 4095)
        printf("ch=%d fraction=%.4f code=%4d approx_v=%.3fV%n", ch, f, code, code * VDD / 4096)
    }
    Thread.sleep(500)

    // --- Synchronous staircase from 0 to full scale on all four channels ---
    // Using setAll with the same fraction across channels keeps them in lock-step
    // and demonstrates simultaneous V_OUT update via Fast Write. A 50 ms pause
    // between steps lets the host controller observe each level on the scope.
    (0..16).each { n ->
        double f = n * STEP
        dac.setAll([f, f, f, f] as double[])          // update all four channels simultaneously, (fractions[4]) → void
        int code = (int) Math.round(f * 4095)
        printf("step=%2d fraction=%.4f code=%4d approx_v=%.3fV%n", n, f, code, code * VDD / 4096)
        Thread.sleep(STEP_MS)
    }

    // --- Reset all channels to 0 V before exit ---
    // Avoids leaving the rail at an arbitrary level when the process ends.
    dac.setAll([0.0d, 0.0d, 0.0d, 0.0d] as double[])  // update all four channels simultaneously, (fractions[4]) → void
} finally {
    transport.close()
}
