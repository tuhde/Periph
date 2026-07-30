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

    RDA5807MMinimal radio(connection);                                      // Create RDA5807M driver, (connection)

    radio.set_frequency(98.5f);                                            // Tune to frequency, (frequency_mhz) → void
    radio.set_volume(5);                                                   // Set volume 0–15, (level) → void
    radio.mute(false);                                                     // Unmute, (enable=false) → void

    while (true) {
        printf("freq=%.1f MHz\n", radio.frequency());                     // Read tuned frequency, () → float MHz
        usleep(1000000);
    }
    return 0;
}
