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

    // --- Scan and print signal strength for each station ---
    // Steps through the FM band 87.5–108 MHz in 100 kHz steps and logs RSSI.
    radio.set_volume(0);                                                   // Mute for scanning, (level=0) → void
    radio.mute(true);                                                      // Mute output, (enable=true) → void
    printf("freq_MHz,signal,stereo\n");
    for (uint32_t khz = 87500; khz <= 108000; khz += 100) {
        radio.set_frequency(khz / 1000.0f);                                // Tune to frequency, (frequency_mhz) → void
        usleep(50000);
        uint8_t signal = radio.signal_strength();                          // Read signal strength 0–127, () → uint8_t
        printf("%.1f,%u,%d\n", khz / 1000.0f, signal, radio.is_stereo());  // is_stereo() → bool
    }
    return 0;
}
