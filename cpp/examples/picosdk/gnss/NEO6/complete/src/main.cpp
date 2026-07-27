#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "UARTConnectionPicoSDK.h"
#include "NEO6.h"

int main(void) {
    // UART0 on GP0 (TX) / GP1 (RX) — pico-sdk default UART pins.
    uart_init(uart0, 9600);
    gpio_set_function(0, GPIO_FUNC_UART);
    gpio_set_function(1, GPIO_FUNC_UART);
    UARTConnectionPicoSDK connection(uart0, 9600);
    NEO6Full gps(connection, /*bus_type=*/0);

    stdio_init_all();

    gps.setRate(1);                                      // Set navigation update rate, (hz) → void
                                                          // writes CFG-RATE with measRate = 1000/hz ms
    gps.setPlatform(0);                                   // Set dynamic platform model, (model 0-8) → void
                                                          // writes CFG-NAV5 with mask=dynModel only
    gps.saveConfig();                                     // Persist current configuration, () → void
                                                          // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM
    while (true) {

    if (gps.update()) {                                  // Read + parse one NMEA sentence, () → bool
        printf("%.6f", gps.latitude());
        printf(", ");
        printf("%.6f", gps.longitude());
        printf(", ");
        printf("%.1f\n", gps.altitude());
                                                          // decimal degrees, decimal degrees, meters MSL
        printf("%d", gps.speed());                       // Speed over ground, () → m/s
        printf(", ");
        printf("%d\n", gps.course());                    // Course over ground, () → deg
        printf("%d", gps.utcTime());                      // UTC time of last fix sentence, () → hhmmss.ss
        printf(", ");
        printf("%d\n", gps.utcDate());                    // UTC date of last RMC sentence, () → ddmmyy
        printf("%d\n", gps.hdop());                       // Horizontal dilution of precision, () → float

        uint8_t payload[256];
        size_t payloadLen = 0;
        if (gps.pollUbx(0x01, 0x03, payload, payloadLen, sizeof(payload))) {  // Poll a UBX message, (msg_class, msg_id, out_payload, out_len, max_len) → bool
            printf("NAV-STATUS payload bytes: ");
            printf("%d\n", payloadLen);
        }

        gps.coldStart();                                  // Force a cold start via CFG-RST, () → void
    }
    sleep_ms(50);
        sleep_ms(10);
    }

    return 0;
}
