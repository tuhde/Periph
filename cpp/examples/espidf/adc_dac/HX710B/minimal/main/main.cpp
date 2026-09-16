// Auto-generated ESP-IDF example for HX710B (Minimal).
// Mirrors the Arduino HX710B_Minimal example using the
// HX711ConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "HX711ConnectionESPIDF.h"
#include "HX710B.h"

extern "C" void app_main(void) {
    HX711ConnectionESPIDF connection(static_cast<gpio_num_t>(19), static_cast<gpio_num_t>(18));
    HX710BMinimal<HX711ConnectionESPIDF> chip(connection);  // Create HX710B driver
    int32_t raw;
    while (1) {
        raw = chip.read_raw();                                  // Block until data ready and read, () → int32_t ADC counts
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
