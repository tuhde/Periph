#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LM25066.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    LM25066Minimal hotswap(connection, 1.0f, false);   // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    while (true) {
        printf("VIN %.3f V  VOUT %.3f V\n", (double)hotswap.voltage_in(), (double)hotswap.voltage_out()); // Read VIN / VOUT, () → float V each
        printf("IIN %.3f A  PIN %.2f W  T %.1f C\n", (double)hotswap.current(), (double)hotswap.power(), (double)hotswap.temperature()); // Read A / W / MOSFET °C, () → float each
        sleep_ms(1000);
    }
    return 0;
}
