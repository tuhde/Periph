#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MCP4XXX.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x58);
    MCP4XXXMinimal pot(connection, MCP4XXXPart::MCP4532, 10000.0f, 75.0f); // Create MCP4XXX driver, (connection, part, r_ab=10000 Ω, r_wiper=75 Ω)

    stdio_init_all();
    pot.init();                                              // Bus check, () → void

    pot.set_wiper(32, 0);                                    // Set wiper, (code 0-128, channel=0) → void
    pot.set_resistance(2200.0f, 0);                          // Set W-B resistance, (ohms, channel=0) → void

    while (true) {
        uint16_t code = pot.get_wiper(0);                        // Read wiper, (channel=0) → uint16_t 0-128
        float ohms = pot.get_resistance(0);                      // Read W-B resistance, (channel=0) → float Ω
        printf("code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        sleep_ms(1000);
    }
}
