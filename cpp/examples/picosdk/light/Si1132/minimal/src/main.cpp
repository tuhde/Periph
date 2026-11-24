#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Si1132.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, Si1132Minimal::I2C_ADDRESS);
    Si1132Minimal sensor(connection);                               // Create Si1132 driver, (connection)
    sensor.init();                                                  // Initialize the chip, () → bool
    for (int i = 0; i < 10; i++) {
        float uv = 0; uint16_t vis = 0, ir = 0;
        sensor.read(uv, vis, ir);                                   // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
        printf("UV %.2f visible %lu infrared %lu\n", (double)(uv), (unsigned long)(vis), (unsigned long)(ir));
        sleep_ms(1000);
    }
    return 0;
}
