#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP4725.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CConnectionLinux connection(bus, addr);

    MCP4725Full dac(connection);                                            // Create MCP4725 driver, (connection)

    dac.set_voltage(0.75f);                                                // Set output as fraction of VDD, (fraction 0.0–1.0) → void
                                                                           // fast-write command; not persisted
    dac.set_raw(3000);                                                     // Set raw DAC code, (code 0–4095) → void
                                                                           // 12-bit code, VOUT = VDD × code / 4096
    dac.set_voltage_eeprom(0.5f);                                          // Set output and store in EEPROM, (fraction 0.0–1.0) → void
                                                                           // value is restored at the next power-up
    while (!dac.is_eeprom_ready()) usleep(1000);                           // Poll EEPROM write completion, () → bool
                                                                           // EEPROM write takes up to 50 ms
    dac.set_raw_eeprom(2048);                                              // Set raw code and store in EEPROM, (code 0–4095) → void
    while (!dac.is_eeprom_ready()) usleep(1000);

    MCP4725Full::ReadResult state = dac.read();                            // Read DAC and EEPROM state, () → ReadResult
                                                                           // current code/fraction plus stored EEPROM code and ready flag
    printf("code=%u fraction=%.3f eeprom_code=%u ready=%d\n",
           state.code, (double)state.voltage_fraction, state.eeprom_code, state.eeprom_ready);

    dac.set_power_down(MCP4725Full::PD_100K_GND);                          // Enter power-down, (mode PD_1K_GND/PD_100K_GND/PD_500K_GND) → void
                                                                           // output pulled to GND through the selected resistor
    usleep(1000);
    dac.wake_up();                                                         // Wake from power-down, () → void
                                                                           // general-call wake-up; affects every MCP4725 on the bus
    dac.reset();                                                           // General-call reset, () → void
                                                                           // reloads the DAC from EEPROM
    return 0;
}
