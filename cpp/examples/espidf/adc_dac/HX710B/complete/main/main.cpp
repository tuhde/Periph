// Auto-generated ESP-IDF example for HX710B (Complete).
// Mirrors the Arduino HX710B_Complete example using the
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
    chip.is_ready();                                  // Check data ready (non-blocking), () → bool
    chip.read_raw();                                  // Block until data ready and read, () → int32_t ADC counts
    chip.set_rate(40);                                // Select output rate, (10|40) → void
    // issues a dummy conversion to apply the new rate
    chip.set_rate(10);                                // Select output rate, (10|40) → void
    chip.read_average(10);                            // Average N readings, (times=10) → int32_t
    chip.tare(10);                                    // Capture zero offset, (times=10) → void
    chip.get_offset();                                // Read stored tare offset, () → int32_t
    chip.set_scale(2280.0f);                          // Set calibration scale, (factor) → void
    chip.get_scale();                                 // Read scale factor, () → float
    chip.read_weight(5);                              // Read calibrated weight, (times=1) → float
    chip.read_supply_diff_raw();                      // Read raw DVDD−AVDD supply-difference code, () → int32_t
    chip.power_down();                                // Enter power-down, () → void
    chip.power_up();                                  // Exit power-down, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
