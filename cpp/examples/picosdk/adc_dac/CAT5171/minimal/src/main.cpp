#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "CAT5171.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, CAT5171Minimal::I2C_ADDRESS);
    CAT5171Minimal pot(connection);                     // Create CAT5171 driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    static const int codes[] = {0, 64, 128, 192, 255};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–255) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–255
        float r = pot.getResistance();                  // Read W–B resistance, () → float Ω
        printf("code %d read %d -> %.1f ohm\n", codes[i], (int)w, (double)r);
        sleep_ms(500);
    }
    pot.setResistance(22000.0f);                        // Set W–B resistance, (ohms Ω) → void
    return 0;
}
