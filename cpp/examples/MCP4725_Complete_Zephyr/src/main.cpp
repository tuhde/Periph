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

    dac.set_voltage(0.5);
    dac.set_raw(2048);
    dac.set_voltage_eeprom(0.75);
    dac.set_raw_eeprom(3000);

    MCP4725ReadResult result = dac.read();
    printk("Code:            %u\n", result.code);
    printk("Voltage frac:   %.4f\n", (double)result.voltage_fraction);
    printk("Power down:      %u\n", result.power_down);
    printk("EEPROM code:     %u\n", result.eeprom_code);
    printk("EEPROM PD:       %u\n", result.eeprom_power_down);
    printk("EEPROM ready:    %d\n", result.eeprom_ready);
    printk("POR:             %d\n", result.por);

    dac.set_power_down(1);
    dac.wake_up();
    dac.reset();
    dac.is_eeprom_ready();

    return 0;
}