#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP4728.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CConnectionLinux connection(bus, addr);

    MCP4728Full dac(connection);                                            // Create MCP4728 driver, (connection)

    dac.set_voltage(0, 1000);                                              // Set channel 0 output, (ch=0, value 0–4095) → void
    dac.set_voltage(1, 2000);                                              // Set channel 1 output, (ch=1, value) → void
    dac.set_voltage(2, 3000);                                              // Set channel 2 output, (ch=2, value) → void
    dac.set_voltage(3, 4095);                                              // Set channel 3 output, (ch=3, value) → void
    dac.write_eeprom(0, 1000);                                             // Write channel 0 to EEPROM, (ch=0, value) → void
    dac.set_vref(0, true);                                                 // Set internal Vref for channel 0, (ch, internal=true) → void
    dac.set_gain(0, true);                                                 // Set 2× gain for channel 0, (ch, gain2x=true) → void
    dac.power_down(1, MCP4728Full::PD_100K);                               // Power-down channel 1, (ch, mode) → void
    dac.power_down(1, MCP4728Full::PD_NORMAL);                             // Wake channel 1, (ch, PD_NORMAL) → void
    printf("done\n");
    return 0;
}
