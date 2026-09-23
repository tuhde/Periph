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

    dac.set_voltage(0, 0.25f);                                             // Set channel output as fraction of VDD, (channel 0–3, fraction 0.0–1.0) → void
                                                                           // fast write; not persisted
    dac.set_raw(1, 2048);                                                  // Set raw channel code, (channel 0–3, code 0–4095) → void
    const float fractions[4] = {0.1f, 0.2f, 0.3f, 0.4f};
    dac.set_all(fractions);                                                // Set all four channels, (fractions[4]) → void
                                                                           // one fast-write transaction for A–D

    dac.set_voltage_eeprom(0, 0.5f, MCP4728Full::VREF_INTERNAL, MCP4728Full::GAIN_X1);  // Set channel and store in EEPROM, (channel, fraction, vref, gain) → void
                                                                           // internal 2.048 V reference, gain ×1
    while (!dac.is_eeprom_ready()) usleep(1000);                           // Poll EEPROM write completion, () → bool
    dac.set_raw_eeprom(1, 1024, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);      // Set raw code and store in EEPROM, (channel, code, vref, gain) → void
    while (!dac.is_eeprom_ready()) usleep(1000);
    const uint8_t vrefs[4] = {MCP4728Full::VREF_INTERNAL, MCP4728Full::VREF_INTERNAL,
                              MCP4728Full::VREF_EXTERNAL, MCP4728Full::VREF_EXTERNAL};
    const uint8_t gains[4] = {MCP4728Full::GAIN_X2, MCP4728Full::GAIN_X1,
                              MCP4728Full::GAIN_X1, MCP4728Full::GAIN_X1};
    dac.set_all_eeprom(fractions, vrefs, gains);                           // Store all four channels in EEPROM, (fractions[4], vrefs[4], gains[4]) → void
    while (!dac.is_eeprom_ready()) usleep(1000);

    dac.set_vref(MCP4728Full::VREF_INTERNAL, MCP4728Full::VREF_INTERNAL,
                 MCP4728Full::VREF_EXTERNAL, MCP4728Full::VREF_EXTERNAL);  // Select reference per channel, (vref_a, vref_b, vref_c, vref_d) → void
                                                                           // internal 2.048 V or VDD
    dac.set_gain(MCP4728Full::GAIN_X2, MCP4728Full::GAIN_X1,
                 MCP4728Full::GAIN_X1, MCP4728Full::GAIN_X1);              // Select gain per channel, (gain_a, gain_b, gain_c, gain_d) → void
                                                                           // ×2 only applies with the internal reference
    dac.set_power_down(MCP4728Full::PD_NORMAL, MCP4728Full::PD_100K_GND,
                       MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL);    // Set power-down per channel, (pd_a, pd_b, pd_c, pd_d) → void
                                                                           // channel B pulled to GND through 100 kΩ

    MCP4728Full::ReadResult state = dac.read();                            // Read all channel and EEPROM state, () → ReadResult
                                                                           // live register and EEPROM copy for A–D
    for (int ch = 0; ch < 4; ++ch) {
        printf("ch%d code=%u vref=%u gain=%u pd=%u eeprom_code=%u\n", ch,
               state.channel[ch].code, state.channel[ch].vref, state.channel[ch].gain,
               state.channel[ch].power_down, state.channel[ch].eeprom_code);
    }

    dac.software_update();                                                 // General-call software update, () → void
                                                                           // latches pending input registers to the outputs
    dac.wake_up();                                                         // General-call wake-up, () → void
                                                                           // clears power-down on every channel
    dac.reset();                                                           // General-call reset, () → void
                                                                           // reloads all channels from EEPROM
    return 0;
}
