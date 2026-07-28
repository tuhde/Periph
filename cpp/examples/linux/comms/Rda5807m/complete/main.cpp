#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Rda5807m.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x10;
    I2CConnectionLinux connection(bus, addr);

    Rda5807mFull radio(connection);                                         // Create RDA5807M driver, (connection)

    radio.set_frequency(98500);                                            // Tune to frequency, (kHz) → void
    radio.set_volume(8);                                                   // Set volume 0–15, (volume) → void
    radio.set_mute(false);                                                 // Unmute, (mute=false) → void
    radio.set_bass(true);                                                  // Enable bass boost, (enable) → void
    radio.set_mono(false);                                                 // Enable stereo, (mono=false) → void
    radio.seek(true);                                                      // Seek upward, (up=true) → void
    usleep(500000);
    printf("freq=%u kHz  rssi=%d  stereo=%d\n",
           radio.frequency(), radio.rssi(), radio.is_stereo());            // Read freq kHz () → uint32_t ; rssi () → int ; stereo () → bool
    radio.set_rds(true);                                                   // Enable RDS decoder, (enable) → void
    radio.set_mute(true);                                                  // Mute, (mute=true) → void
    return 0;
}
