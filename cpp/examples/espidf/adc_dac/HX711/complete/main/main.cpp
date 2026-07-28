// Auto-generated ESP-IDF example for HX711 (Complete).
// Mirrors the Arduino HX711_Complete example using the
// HX711ConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "HX711ConnectionESPIDF.h"
#include "HX711.h"

extern "C" void app_main(void) {
    HX711ConnectionESPIDF connection(static_cast<gpio_num_t>(19), static_cast<gpio_num_t>(18));
    HX711Full chip(connection);  // Create HX711 driver
    int32_t raw;
    int32_t avg;
    int32_t offset;
    float scale;
    float weight;
    chip.is_ready();                                  // Check data ready (non-blocking), () → bool
    chip.read_raw();                                  // Block until data ready and read, () → int32_t ADC counts
    chip.read_average(10);                            // Average N readings, (times=10) → int32_t
    chip.tare(10);                                    // Capture zero offset, (times=10) → void
    chip.get_offset();                                // Read stored tare offset, () → int32_t
    chip.set_scale(2280.0f);                          // Set calibration scale, (factor) → void
    chip.get_scale();                                 // Read scale factor, () → float
    chip.read_weight(5);                              // Read calibrated weight, (times=1) → float
    chip.set_gain(64);                                // Set gain, (128/64/32) → void
    // issues a dummy conversion to apply the new gain
    chip.set_gain(128);                               // Set gain, (128/64/32) → void
    chip.power_down();                                // Enter power-down, () → void
    chip.power_up();                                  // Exit power-down, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
