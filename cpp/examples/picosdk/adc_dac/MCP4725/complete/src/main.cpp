#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "MCP4725.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x60);
MCP4725Full dac(transport);

int main(void) {

    stdio_init_all();
    while (true) {

    dac.set_voltage(0.75);
    dac.set_raw(3000);
    dac.set_voltage_eeprom(0.5);
    dac.set_raw_eeprom(2048);
    MCP4725Full::ReadResult state = dac.read();
    printf("code=");
    printf("%d", state.code);
    printf(" ready=");
    printf("%d\n", state.eeprom_ready);
    dac.set_power_down(MCP4725Full.PD_100K_GND);
    dac.wake_up();
    dac.reset();
    dac.is_eeprom_ready();
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
