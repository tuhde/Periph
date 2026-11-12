#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DS1881.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, DS1881Minimal::I2C_ADDRESS);
    DS1881Minimal amp(connection);                      // Create DS1881 driver, (connection)
    amp.init();                                         // Apply defaults, () → void
    static const int dbs[] = {0, 10, 20, 40};
    for (int i = 0; i < 4; i++) {
        amp.setAttenuation(0, (float)dbs[i]);           // Set attenuation, (channel 0–1, db dB) → void
        amp.setAttenuation(1, (float)dbs[i]);           // Set attenuation, (channel 0–1, db dB) → void
        float a = amp.getAttenuation(0);                // Read attenuation, (channel 0–1) → float dB
        printf("attenuation %d dB, read back %d dB\n", dbs[i], (int)a);
        sleep_ms(500);
    }
    amp.mute(0);                                        // Mute channel, (channel 0–1) → void
    return 0;
}
