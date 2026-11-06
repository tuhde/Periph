#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AD5248.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x2C);
    AD5248Minimal pot(connection, 10000.0f, 160.0f);             // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

    stdio_init_all();

    pot.set_wiper(0, 64);                                    // Set channel 0 wiper, (channel 0-1, code 0-255) → void
    pot.set_resistance(1, 2200.0f);                          // Set channel 1 W-B resistance, (channel 0-1, ohms) → void

    while (true) {
        uint8_t code = pot.get_wiper(0);                     // Read channel 0 wiper, (channel 0-1) → uint8_t 0-255
        float ohms = pot.get_resistance(0);                  // Read channel 0 W-B resistance, (channel 0-1) → float Ω
        printf("ch0 code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        sleep_ms(1000);
    }
}
