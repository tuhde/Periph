#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP4728.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MCP4728_ADDR 0x60

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MCP4728_ADDR);
    MCP4728Full dac(transport);

    while (1) {
        dac.set_voltage(0, 0.75f);
        dac.set_raw(2, 3000);
        float fractions[4] = {0.1f, 0.2f, 0.3f, 0.4f};
        dac.set_all(fractions);
        dac.set_voltage_eeprom(0, 0.5f, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
        dac.set_raw_eeprom(1, 2048, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
        float fracs[4]   = {0.0f, 0.25f, 0.5f, 0.75f};
        uint8_t vrefs[4] = {0, 0, 0, 0};
        uint8_t gains[4] = {1, 1, 1, 1};
        dac.set_all_eeprom(fracs, vrefs, gains);
        dac.set_vref(0, 0, 0, 0);
        dac.set_gain(1, 1, 1, 1);
        dac.set_power_down(MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL,
                           MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL);
        MCP4728Full::ReadResult state = dac.read();
        printk("ch0 code=%u eeprom_ready=%d\n", state.channel[0].code, state.eeprom_ready);
        dac.software_update();
        dac.wake_up();
        dac.reset();
        dac.is_eeprom_ready();
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
