// Demo for the APDS-9930 — adaptive backlight + screen-lock scenario.

#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/i2c.h"
#include "I2CConnectionPicoSDK.h"
#include "Apds9930.h"

#define DIM_LUX_THRESHOLD 10.0f
#define PROX_SCREEN_OFF  400

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x39);                                   // Create I2C connection, (i2c0, addr=0x39) → I2CConnectionPicoSDK
    APDS9930Full apds(connection);                                                  // Create APDS-9930 Full, (connection) → APDS9930Full
                                                                                   // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive

    stdio_init_all();
    sleep_ms(110);
    // --- Sample lux and proximity once per second for 30 cycles ---
    for (int i = 0; i < 30; i++) {
        sleep_ms(1000);
        float lx = apds.lux();                                                      // Read ambient illuminance, () → float lx
        uint16_t p = apds.proximity();                                              // Read proximity count, () → uint16_t count
        printf("lux=%.1f lx  proximity=%u\n", lx, p);
        if (lx < DIM_LUX_THRESHOLD) printf("  -> dim backlight\n");
        if (p > PROX_SCREEN_OFF) printf("  -> disable screen\n");
    }
    return 0;
}