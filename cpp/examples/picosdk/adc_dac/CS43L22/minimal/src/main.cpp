#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "CS43L22.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, CS43L22Minimal::I2C_ADDRESS);
    CS43L22Minimal dac(connection);                  // Create CS43L22 driver, (connection, resPin=nullptr)
    dac.init();                                      // Reset, verify ID, load defaults, () → bool
    dac.start();                                     // Power up the DAC once MCLK is running, () → void
    dac.setVolume(-20.0f);                           // Set master volume, (db -102–12 dB) → void
    dac.mute(false);                                 // Unmute the headphone outputs, (on=true) → void
    for (int i = 0; i < 5; i++) {
        float v = dac.getVolume();                   // Read back master volume, () → float dB
        printf("volume %d dB\n", (int)v);
        sleep_ms(1000);
    }
    dac.stop();                                      // Power down the DAC, () → void
    return 0;
}
