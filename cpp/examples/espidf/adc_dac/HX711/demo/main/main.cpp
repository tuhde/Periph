// Auto-generated ESP-IDF example for HX711 (Demo).
// Mirrors the Arduino HX711_Demo example using the
// HX711TransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "HX711TransportESPIDF.h"
#include "HX711.h"

extern "C" void app_main(void) {
    HX711TransportESPIDF transport(static_cast<gpio_num_t>(19), static_cast<gpio_num_t>(18));
    HX711Full chip(transport);  // Create HX711 driver
    int32_t raw;
    int32_t avg;
    int32_t offset;
    float scale;
    float weight;
    // --- Tare, calibrate, and read calibrated weight ---
    // Tare zeros the scale; calibration is (raw_avg - offset) / scale. Apply a known weight and adjust scale until the readout matches.

    chip.tare(10);                                    // Capture zero offset, (times=10) → void
    chip.set_scale(2280.0f);                          // Set calibration scale, (factor) → void
    chip.read_weight(5);                              // Read calibrated weight, (times=1) → float
    chip.read_raw();                                  // Block until data ready and read, () → int32_t ADC counts
    vTaskDelay(pdMS_TO_TICKS(1000));
}
