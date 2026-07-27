// Auto-generated ESP-IDF example for NEO6 (Demo).
// Mirrors the Arduino NEO6_Demo example using the
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
    // --- Stream NMEA from the module, print fix state ---
    // 9600 baud, 1 Hz GGA + RMC + VTG. update() returns true when a GGA with a valid fix is parsed.

    chip.update();                                    // Read NMEA and parse, () → bool true if a fix was parsed
    chip.latitude();                                  // Read latitude, () → float deg
    chip.longitude();                                 // Read longitude, () → float deg
    chip.fix();                                       // Read GGA fix quality, () → int
    chip.satellites();                                // Read satellite count, () → int
    vTaskDelay(pdMS_TO_TICKS(1000));
}
