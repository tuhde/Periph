#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ES8311.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x18;
    I2CConnectionLinux connection(bus, addr);
    ES8311Minimal codec(connection);  // Create ES8311 driver, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)
    codec.set_mic_gain(24.0f);                                  // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → void
    codec.set_mute(false);                                      // Unmute the DAC, (muted=false) → void
    codec.set_volume(-20.0f);                                   // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → void
    usleep(5000000);
    codec.set_mute(true);                                       // Mute the DAC, (muted=true) → void
    return 0;
}
