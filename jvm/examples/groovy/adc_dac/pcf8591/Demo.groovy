///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Pcf8591Full

/**
 * Potentiometer feedback: read AIN0, write the same value back to the DAC.
 * LED brightness on AOUT tracks the potentiometer wiper.
 */

final VREF    = 3.3
final VAGND   = 0.0
final STEPS   = 20
final STEP_MS = 200

def transport = new I2CTransport(1, 0x48)          // open I²C bus 1, device 0x48, (bus, address) → I2CTransport
try {
    def adc = new Pcf8591Full(transport)                    // construct driver, (transport) → Pcf8591Full

    // --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
    // Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
    // the 0–255 value to a DAC output value, and write it to AOUT — the LED
    // brightness tracks the potentiometer. This demonstrates the ADC→DAC
    // feedback path inside a single chip.
    adc.configure(Pcf8591Full.MODE_4_SINGLE_ENDED, false, true)   // configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → void
                                                                    // single-ended mode with DAC output enabled
    (0..<STEPS).each { n ->
        int raw = adc.readChannel(0)                              // read single channel, (channel=0–3) → int
        double vin  = VAGND + raw * (VREF - VAGND) / 256.0
        adc.setDac(raw)                                           // enable DAC and set raw value, (value=0–255) → void
        double vout = VAGND + raw * (VREF - VAGND) / 256.0
        printf("n=%2d raw=%3d vin=%.3fV  vout=%.3fV%n", n, raw, vin, vout)
        Thread.sleep(STEP_MS)
    }

    // --- Return output to 0 V before exit ---
    // Avoids leaving the rail at an arbitrary level when the process ends.
    adc.setDac(0)                                                 // enable DAC and set raw value, (value=0–255) → void

} finally {
    transport.close()
}
