///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Pcf8591Full

/**
 * Potentiometer feedback: read AIN0, write the same value back to the DAC.
 * LED brightness on AOUT tracks the potentiometer wiper.
 */

private const val VREF    = 3.3
private const val VAGND   = 0.0
private const val STEPS   = 20
private const val STEP_MS = 200L

fun main() {
    I2CTransport(1, 0x48).use { transport ->           // open I²C bus 1, device 0x48, (bus, address) → I2CTransport
        val adc = Pcf8591Full(transport)                        // construct driver, (transport) → Pcf8591Full

        // --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
        // Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
        // the 0–255 value to a DAC output value, and write it to AOUT — the LED
        // brightness tracks the potentiometer. This demonstrates the ADC→DAC
        // feedback path inside a single chip.
        adc.configure(Pcf8591Full.MODE_4_SINGLE_ENDED, false, true)   // configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → Unit
                                                                        // single-ended mode with DAC output enabled
        for (n in 0 until STEPS) {
            val raw = adc.readChannel(0)                            // read single channel, (channel=0–3) → Int
            val vin  = VAGND + raw * (VREF - VAGND) / 256.0
            adc.setDac(raw)                                         // enable DAC and set raw value, (value=0–255) → Unit
            val vout = VAGND + raw * (VREF - VAGND) / 256.0
            println("n=%2d raw=%3d vin=%.3fV  vout=%.3fV".format(n, raw, vin, vout))
            Thread.sleep(STEP_MS)
        }

        // --- Return output to 0 V before exit ---
        // Avoids leaving the rail at an arbitrary level when the process ends.
        adc.setDac(0)                                              // enable DAC and set raw value, (value=0–255) → Unit
    }
}
