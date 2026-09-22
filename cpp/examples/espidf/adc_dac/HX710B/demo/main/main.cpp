// Auto-generated ESP-IDF example for HX710B (Demo).
// Mirrors the Arduino HX710B_Demo example using the
// HX711ConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "HX711ConnectionESPIDF.h"
#include "HX710B.h"

extern "C" void app_main(void) {
    HX711ConnectionESPIDF connection(static_cast<gpio_num_t>(19), static_cast<gpio_num_t>(18));
    HX710BFull<HX711ConnectionESPIDF> chip(connection);  // Create HX710B driver
    // --- Tare, calibrate, and read calibrated weight, with periodic supply-diff ---
    // Tare zeros the scale; calibration is (raw_avg - offset) / scale. Apply a
    // known weight and adjust scale until the readout matches. The supply-diff
    // channel returns an uncalibrated raw code for relative drift tracking
    // against a known-good baseline (battery-monitoring use case).

    chip.tare(10);                                    // Capture zero offset, (times=10) → void
    chip.set_scale(2280.0f);                          // Set calibration scale, (factor) → void
    chip.read_weight(5);                              // Read calibrated weight, (times=1) → float
    chip.read_supply_diff_raw();                      // Read raw DVDD−AVDD supply-difference code, () → int32_t
    chip.read_raw();                                  // Block until data ready and read, () → int32_t ADC counts
    vTaskDelay(pdMS_TO_TICKS(1000));
}
