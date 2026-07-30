#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "RDA5807M.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x10;
    I2CConnectionLinux connection(bus, addr);

    RDA5807MFull radio(connection);                                         // Create RDA5807M driver, (connection)

    radio.set_frequency(98.5f);                                            // Tune to frequency, (frequency_mhz) → void
    radio.set_volume(8);                                                   // Set volume 0–15, (level) → void
    radio.mute(false);                                                     // Unmute, (enable=false) → void
    radio.set_bass_boost(true);                                            // Enable bass boost, (enable) → void
    radio.set_mono(false);                                                 // Allow stereo, (enable=false) → void
    float freq;
    radio.seek(true, freq);                                                // Seek upward, (up=true, frequency_mhz) → bool
    usleep(500000);
    printf("freq=%.1f MHz  signal=%u  stereo=%d\n",
           radio.frequency(), radio.signal_strength(), radio.is_stereo()); // Read freq MHz () → float ; signal_strength () → uint8_t ; stereo () → bool
    radio.enable_rds(true);                                                // Enable RDS decoder, (enable) → void
    radio.mute(true);                                                      // Mute, (enable=true) → void
    return 0;
}
