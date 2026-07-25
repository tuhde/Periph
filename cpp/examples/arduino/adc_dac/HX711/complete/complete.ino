#include "HX711Transport.h"
#include "HX711.h"

HX711Transport transport(5, 6);
HX711Full<HX711Transport> chip(transport);

void setup() {
    Serial.begin(115200);
}

void loop() {
    bool ready = chip.is_ready();                          // Check if conversion is ready (non-blocking), () → bool
                                                            // returns true when DOUT is LOW
    int32_t raw = chip.read_raw();                         // Read signed 24-bit ADC value at current gain, () → int32_t
                                                            // blocks until DOUT goes LOW, then clocks out 24 bits

    chip.set_gain(64);                                     // Select channel and gain, (gain: 128|64|32) → void
                                                            // 128 → Channel A, 64 → Channel A, 32 → Channel B; issues dummy read to apply
    chip.set_gain(32);
    chip.set_gain(128);

    int32_t avg = chip.read_average(10);                   // Average multiple raw readings, (times=10) → int32_t
                                                            // blocks for `times` complete conversions

    chip.tare(10);                                         // Capture zero offset from 10-reading average, (times=10) → void
                                                            // stores result in internal _offset; call with nothing on the scale
    int32_t offset = chip.get_offset();                    // Return stored tare offset, () → int32_t

    chip.set_scale(420.0f);                                // Set calibration scale factor, (factor: float) → void
                                                            // factor = (read_average() - offset) / known_weight_in_target_unit
    float scale = chip.get_scale();                        // Return current scale factor, () → float

    float weight = chip.read_weight(5);                    // Return calibrated weight, (times=1) → float
                                                            // computes (read_average(times) - offset) / scale
    Serial.println(weight);

    chip.power_down();                                     // Enter power-down mode, () → void
                                                            // holds PD_SCK HIGH for >60 µs
    chip.power_up();                                       // Exit power-down, reset chip, discard settling conversion, () → void
                                                            // resets to Channel A Gain 128; first post-reset conversion discarded internally

    delay(500);
}
