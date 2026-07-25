#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP4725.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MCP4725_ADDR 0x60

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MCP4725_ADDR);
    MCP4725Full dac(transport);

    while (1) {
        dac.set_voltage(0.75f);
        dac.set_raw(3000);
        dac.set_voltage_eeprom(0.5f);
        dac.set_raw_eeprom(2048);
        MCP4725Full::ReadResult state = dac.read();
        printk("code=%u ready=%d\n", state.code, state.eeprom_ready);
        dac.set_power_down(MCP4725Full.PD_100K_GND);
        dac.wake_up();
        dac.reset();
        dac.is_eeprom_ready();
        k_sleep(K_SECONDS(1));
    }
    return 0;
}