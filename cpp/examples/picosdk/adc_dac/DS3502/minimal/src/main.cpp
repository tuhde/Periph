#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DS3502.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, DS3502Minimal::I2C_ADDRESS);
    DS3502Minimal pot(connection, 15.0f, 0.0f);         // Create DS3502 driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    static const int codes[] = {0, 32, 64, 96, 127};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–127) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–127
        float v = pot.getVoltage();                     // Read unloaded RW voltage, () → float V
        printf("code %d read %d -> %.3f V\n", codes[i], (int)w, (double)v);
        sleep_ms(500);
    }
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    return 0;
}
