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

    // --- Sawtooth waveform at ~1 kHz ---
    // Sweeps 0→4095 in 16 steps, each held for ~62 µs → ~1 kHz period.
    while (true) {
        for (uint16_t v = 0; v <= 4095; v += 256) {
            dac.set_voltage(v);                                            // Set DAC output (fast write), (value 0–4095) → void
            usleep(62);
        }
    }
    return 0;
}
