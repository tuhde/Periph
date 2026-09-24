#include <SPI.h>
#include <Periph.h>

#define FILTER_DP_THRESHOLD 0.001f

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(5000000, MSBFIRST, SPI_MODE3);
SPIConnection connection(SPI, TEST_CS_PIN, settings);
AD7706Full adc(connection, 2.5, AD7706Minimal::MCLK_2_4576MHZ);      // Construct and initialise the AD7706, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7706Full

float last_filter_dp = 0.0f;
bool first = true;

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    // --- Configure all three channels for the HVAC manifold-pressure application ---
    // All three pressure transducers are bridge-type with small mV-level output
    // signals, so the AD7706's high-gain (128) pseudo-differential input is ideal.
    // The shared-COMMON architecture lets all three bridges share a single return
    // line instead of three fully-differential pairs.
    adc.configure(1, AD7706Full::GAIN_128, true, true, 50);          // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, AD7706Full::GAIN_128, true, true, 50);          // Configure channel 2, (channel=2, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(3, AD7706Full::GAIN_128, true, true, 50);          // Configure channel 3, (channel=3, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None

    // --- Self-calibrate all three channels before the measurement loop ---
    adc.self_calibrate(1);                                           // Self-calibrate channel 1, (channel=1) → None
    adc.self_calibrate(2);                                           // Self-calibrate channel 2, (channel=2) → None
    adc.self_calibrate(3);                                           // Self-calibrate channel 3, (channel=3) → None
}

void loop() {
    // --- Sample continuously and compute filter differential pressure ---
    // Channel 1 = filter-inlet static, Channel 2 = filter-outlet static, Channel 3 = duct static.
    // filter_dp = ch1 - ch2 is the filter differential pressure (clog indicator).
    float inlet  = adc.read_voltage(1);                              // Read voltage on channel 1, (channel=1) → float V
    float outlet = adc.read_voltage(2);                              // Read voltage on channel 2, (channel=2) → float V
    float duct   = adc.read_voltage(3);                              // Read voltage on channel 3, (channel=3) → float V
    float filter_dp = inlet - outlet;
    if (first || fabs(filter_dp - last_filter_dp) > FILTER_DP_THRESHOLD) {
        Serial.print("→ filter_dp="); Serial.print(filter_dp, 4);
        Serial.print(" V, duct_pressure="); Serial.print(duct, 4);
        Serial.println(" V");
        last_filter_dp = filter_dp;
        first = false;
    }
    delay(200);
}
