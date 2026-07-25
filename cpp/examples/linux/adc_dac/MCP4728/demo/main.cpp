#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "MCP4728.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CTransportLinux transport(bus, addr);

    MCP4728Full dac(transport);                                            // Create MCP4728 driver, (transport)

    // --- Four-channel arbitrary waveform generator ---
    // Outputs sine, triangle, sawtooth, and square on channels 0–3.
    while (true) {
        for (int step = 0; step < 64; step++) {
            float theta = step * 3.14159265f * 2.0f / 64;
            uint16_t sine = (uint16_t)((__builtin_sinf(theta) + 1.0f) * 2047.5f);
            uint16_t tri  = step < 32 ? step * 128 : (63 - step) * 128;
            uint16_t saw  = (uint16_t)(step * 64);
            uint16_t sq   = step < 32 ? 4095 : 0;
            dac.set_voltage(0, sine);                                      // Set channel 0 (sine), (ch=0, value) → void
            dac.set_voltage(1, tri);                                       // Set channel 1 (triangle), (ch=1, value) → void
            dac.set_voltage(2, saw);                                       // Set channel 2 (sawtooth), (ch=2, value) → void
            dac.set_voltage(3, sq);                                        // Set channel 3 (square), (ch=3, value) → void
            usleep(1000);
        }
    }
    return 0;
}
