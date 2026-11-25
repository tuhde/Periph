#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AXP2101.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    sleep_ms(2000);   // let the USB serial console attach
    I2CConnectionPicoSDK connection(i2c0, 0x34);
    AXP2101Minimal chip(connection);                    // Create AXP2101 driver, (connection)

    while (true) {
        float v = chip.batteryVoltage();                // Read battery voltage, () → float V
        uint8_t pct = chip.batteryPercent();            // Read state of charge, () → uint8_t %
        uint8_t state = chip.chargeState();             // Read charge state, () → uint8_t (0 trickle … 5 none)
        bool vbus = chip.vbusPresent();                 // Check input source, () → bool
        printf("%.3f V  %d %%  state=%d  vbus=%s\n", (double)v, (int)pct, (int)state, (vbus) ? "true" : "false");
        sleep_ms(1000);
    }
    return 0;
}
