#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "UARTTransportPicoSDK.h"
#include "NEO6.h"

int main(void) {
    // UART0 on GP0 (TX) / GP1 (RX) — pico-sdk default UART pins.
    uart_init(uart0, 9600);
    gpio_set_function(0, GPIO_FUNC_UART);
    gpio_set_function(1, GPIO_FUNC_UART);
    UARTTransportPicoSDK transport(uart0, 9600);
    NEO6Minimal gps(transport, /*bus_type=*/0);

    stdio_init_all();
    while (true) {

    if (gps.update()) {                                  // Read + parse one NMEA sentence, () → bool
        printf("%.6f", gps.latitude());
        printf(", ");
        printf("%.6f", gps.longitude());
        printf(", ");
        printf("%.1f\n", gps.altitude());
    }
    sleep_ms(50);
        sleep_ms(10);
    }

    return 0;
}
