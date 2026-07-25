#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "MCP4728.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x60);
MCP4728Full dac(transport);

int main(void) {

    stdio_init_all();
    while (true) {

    dac.set_voltage(0, 0.75f);
    dac.set_raw(2, 3000);
    float fractions[4] = {0.1f, 0.2f, 0.3f, 0.4f};
    dac.set_all(fractions);
    dac.set_voltage_eeprom(0, 0.5f, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
    dac.set_raw_eeprom(1, 2048, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
    float fracs[4]    = {0.0f, 0.25f, 0.5f, 0.75f};
    uint8_t vrefs[4]  = {0, 0, 0, 0};
    uint8_t gains[4]  = {1, 1, 1, 1};
    dac.set_all_eeprom(fracs, vrefs, gains);
    dac.set_vref(0, 0, 0, 0);
    dac.set_gain(1, 1, 1, 1);
    dac.set_power_down(MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL,
                       MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL);
    MCP4728Full::ReadResult state = dac.read();
    printf("ch0 code=");
    printf("%d", state.channel[0].code);
    printf(" eeprom_ready=");
    printf("%d\n", state.eeprom_ready);
    dac.software_update();
    dac.wake_up();
    dac.reset();
    dac.is_eeprom_ready();
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
