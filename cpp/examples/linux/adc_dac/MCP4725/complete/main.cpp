#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "MCP4725.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CTransportLinux transport(bus, addr);

    MCP4725Full dac(transport);                                            // Create MCP4725 driver, (transport)

    dac.set_voltage(2048);                                                 // Set DAC output (fast write), (value 0–4095) → void
    dac.write_eeprom(2048);                                                // Write DAC + EEPROM, (value 0–4095) → void
                                                                           // value is preserved across power cycles
    uint16_t stored = dac.read_eeprom();                                   // Read EEPROM value, () → uint16_t
    printf("eeprom=%u\n", stored);
    uint16_t current = dac.read_dac();                                     // Read current DAC register, () → uint16_t
    printf("dac=%u\n", current);
    dac.power_down(MCP4725Full::PD_1K);                                    // Power-down with 1 kΩ pulldown, (mode) → void
    usleep(1000);
    dac.power_down(MCP4725Full::PD_NORMAL);                                // Wake up, (mode=PD_NORMAL) → void
    return 0;
}
