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

    radio.set_frequency(98500);                                            // Tune to frequency, (kHz) → void
    radio.set_volume(5);                                                   // Set volume 0–15, (volume) → void
    radio.set_mute(false);                                                 // Unmute, (mute=false) → void

    while (true) {
        printf("rssi=%d  stereo=%d\n",
               radio.rssi(), radio.is_stereo());                           // Read RSSI 0–127 () → int ; is_stereo() → bool
        usleep(1000000);
    }
    return 0;
}
