// Auto-generated ESP-IDF example for HX710A (Minimal).
// Mirrors the Arduino HX710A_Minimal example using the
// HX711ConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "HX711ConnectionESPIDF.h"
#include "HX710A.h"

extern "C" void app_main(void) {
    HX711ConnectionESPIDF connection(static_cast<gpio_num_t>(19), static_cast<gpio_num_t>(18));
    HX710AMinimal chip(connection);  // Create HX710A driver
    int32_t raw;
    while (1) {
    chip.read_raw();                                  // Block until data ready and read, () → int32_t ADC counts
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
