#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADE7953.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x38);
    ADE7953Minimal ade(connection, 251.0f, 30.0f);                  // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    stdio_init_all();
    sleep_ms(2000);
    while (true) {
        float v = ade.voltage();                                    // Read bus voltage, () → V
        float a = ade.current();                                    // Read load current, () → A
        float p = ade.activePower();                                // Read active power, () → W
        float e = ade.activeEnergy();                               // Read active energy, () → Wh
        printf("V=%.2f A=%.3f P=%.2f E=%.4f\n", v, a, p, e);
        sleep_ms(1000);
    }
}