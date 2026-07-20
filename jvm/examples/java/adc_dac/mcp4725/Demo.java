///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.adc_dac.Mcp4725Full;

/**
 * Triangle wave: ramp the DAC from 0 to full scale and back in 1/20 steps,
 * printing the fraction and approximate voltage at each step (assumes 3.3 V supply).
 * Makes a visible sawtooth on an oscilloscope and demonstrates 12-bit resolution.
 */
public class Demo {

    private static final double VDD = 3.3;
    private static final int    STEPS = 20;
    private static final long   STEP_MS = 100;

    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x60)) {      // open I²C bus 1, device 0x60, (bus, address) → I2CTransport
            var dac = new Mcp4725Full(transport, null);               // construct driver (no general call needed for this demo), (transport, generalCall) → Mcp4725Full

            // --- Ramp up from 0 V to VDD ---
            // Each step covers 1/20 of full scale (~163 mV on a 3.3 V rail).
            // 100 ms per step gives a 2-second rise time — slow enough to observe
            // on a multimeter and fast enough to show a clean ramp on an oscilloscope.
            for (int i = 0; i <= STEPS; i++) {
                double fraction = (double) i / STEPS;
                dac.setVoltage(fraction);                              // set output level, (fraction=0.0–1.0) → void
                System.out.printf("↑ fraction=%.2f  V≈%.3f V%n", fraction, fraction * VDD);
                Thread.sleep(STEP_MS);
            }

            // --- Ramp down from VDD back to 0 V ---
            // Descending half of the triangle wave.
            for (int i = STEPS - 1; i >= 0; i--) {
                double fraction = (double) i / STEPS;
                dac.setVoltage(fraction);                              // set output level, (fraction=0.0–1.0) → void
                System.out.printf("↓ fraction=%.2f  V≈%.3f V%n", fraction, fraction * VDD);
                Thread.sleep(STEP_MS);
            }

            // --- Return output to 0 V before exit ---
            // Avoids leaving the rail at an arbitrary level when the process ends.
            dac.setRaw(0);                                             // set output to 0 V, (code=0–4095) → void

        }
    }
}
