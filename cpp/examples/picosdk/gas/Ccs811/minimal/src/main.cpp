#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Ccs811.h"

int main(void) {
    stdio_init_all();
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5A);
    CCS811Minimal sensor(connection);                              // Create CCS811 driver, (connection) → void
    sensor.init();                                                 // Check HW_ID, start application, mode 1, () → bool
    while (true) {
        uint16_t eco2 = 0, tvoc = 0;
        bool ok = sensor.read(eco2, tvoc);                         // Wait for the next sample, (eco2_ppm&, tvoc_ppb&) → bool
        printf("eCO2 %u ppm TVOC %u ppb (ok=%u)\n", (unsigned)(eco2), (unsigned)(tvoc), (unsigned)(ok));
    }
    return 0;
}
