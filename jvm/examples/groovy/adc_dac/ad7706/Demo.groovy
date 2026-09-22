///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.adc_dac.Ad7706Full
import it.uhde.periph.chips.adc_dac.Ad7706Minimal

final FILTER_DP_THRESHOLD = 0.001f

def connection = new SPIConnection(0, 0, 3, 5_000_000)               // open SPI bus 0, device 0, Mode 3, 5 MHz
def adc = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_2_4576MHZ) // construct and initialise the AD7706, (connection, vref=2.5 V, mclkHz=2_457_600 Hz) → Ad7706Full

try {
    // --- Configure all three channels for the HVAC manifold-pressure application ---
    // All three pressure transducers are bridge-type with small mV-level output
    // signals, so the AD7706's high-gain (128) pseudo-differential input is ideal.
    // The shared-COMMON architecture lets all three bridges share a single return
    // line instead of three fully-differential pairs.
    adc.configure(1, Ad7706Full.GAIN_128, true, true, 50)             // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, Ad7706Full.GAIN_128, true, true, 50)             // Configure channel 2, (channel=2, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(3, Ad7706Full.GAIN_128, true, true, 50)             // Configure channel 3, (channel=3, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None

    // --- Self-calibrate all three channels before the measurement loop ---
    adc.selfCalibrate(1)                                               // Self-calibrate channel, (channel=1) → None
    adc.selfCalibrate(2)                                               // Self-calibrate channel, (channel=2) → None
    adc.selfCalibrate(3)                                               // Self-calibrate channel, (channel=3) → None

    // --- Sample continuously and compute filter differential pressure ---
    // Channel 1 = filter-inlet static, Channel 2 = filter-outlet static, Channel 3 = duct static.
    // filter_dp = ch1 - ch2 is the filter differential pressure (clog indicator).
    def lastFilterDp = null
    while (true) {
        def inlet  = adc.readVoltage(1)                                  // Read voltage, (channel=1) → float V
        def outlet = adc.readVoltage(2)                                  // Read voltage, (channel=2) → float V
        def duct   = adc.readVoltage(3)                                  // Read voltage, (channel=3) → float V
        def filterDp = inlet - outlet
        if (lastFilterDp == null || Math.abs(filterDp - lastFilterDp) > FILTER_DP_THRESHOLD) {
            println(String.format("→ filter_dp=%.4f V, duct_pressure=%.4f V", filterDp, duct))
            lastFilterDp = filterDp
        }
        Thread.sleep(200)
    }
} finally {
    connection.close()
}
