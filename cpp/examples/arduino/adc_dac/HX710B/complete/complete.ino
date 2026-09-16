#include "HX711Connection.h"
#include "HX710B.h"

HX711Connection connection(5, 6);
HX710BFull<HX711Connection> chip(connection);

void setup() {
    Serial.begin(115200);
}

void loop() {
    bool ready = chip.is_ready();                          // Check if conversion is ready (non-blocking), () → bool
                                                            // returns true when DOUT is LOW
    int32_t raw = chip.read_raw();                         // Read signed 24-bit differential-input value, () → int32_t
                                                            // blocks until DOUT goes LOW, then clocks out 24 bits

    chip.set_rate(40);                                     // Select differential-input output rate, (rate: 10|40) → void
                                                            // takes effect after next read; issues dummy read to apply
    chip.set_rate(10);                                     // (restores default 10 SPS)

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

    int32_t supp_raw = chip.read_supply_diff_raw();        // Read raw DVDD−AVDD supply-difference code, () → int32_t
                                                            // uncalibrated ADC code, no absolute LSB-to-volts scale
    Serial.println(supp_raw);

    chip.power_down();                                     // Enter power-down mode, () → void
                                                            // holds PD_SCK HIGH for >60 µs
    chip.power_up();                                       // Exit power-down, reset chip, discard settling conversion, () → void
                                                            // resets to differential input, gain 128, 10 SPS

    delay(500);
}
