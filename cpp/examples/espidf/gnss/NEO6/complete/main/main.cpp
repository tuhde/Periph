// Auto-generated ESP-IDF example for NEO6 (Complete).
// Mirrors the Arduino NEO6_Complete example using the
// UARTConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "UARTConnectionESPIDF.h"
#include "NEO6.h"

extern "C" void app_main(void) {
    uart_config_t uart_cfg = {
        .baud_rate = 9600,
        .data_bits = UART_DATA_8_BITS,
        .parity    = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
    };
    uart_driver_install(UART_NUM_1, 1024, 1024, 0, NULL, 0);
    uart_param_config(UART_NUM_1, &uart_cfg);
    uart_set_pin(UART_NUM_1, 17, 16, -1, -1);  // TX=17, RX=16

    UARTConnectionESPIDF connection(UART_NUM_1);
    NEO6Full chip(connection);  // Create NEO6 driver
    float lat, lon, alt, spd, crs, hdop;
    int fix, sats;
    const char *utct, *utcd;
    chip.update();                                    // Read NMEA and parse, () → bool true if a fix was parsed
    chip.latitude();                                  // Read latitude, () → float deg
    chip.longitude();                                 // Read longitude, () → float deg
    chip.altitude();                                  // Read altitude, () → float m
    chip.fix();                                       // Read GGA fix quality, () → int
    chip.satellites();                                // Read satellite count, () → int
    chip.speed();                                     // Read speed, () → float m/s
    chip.course();                                    // Read course, () → float deg
    chip.utcTime();                                   // Read UTC time, () → const char*
    chip.utcDate();                                   // Read UTC date, () → const char*
    chip.hdop();                                      // Read HDOP, () → float
    chip.setRate(1);                                  // Set nav rate, (hz 1-5) → void
    chip.setPlatform(6);                              // Set platform model, (model 0-8) → void
    chip.coldStart();                                 // Force cold start, () → void
    chip.saveConfig();                                // Save config to BBRAM/flash, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
