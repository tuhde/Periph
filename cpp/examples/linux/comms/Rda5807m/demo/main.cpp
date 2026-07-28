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

    // --- Scan and print signal strength for each station ---
    // Steps through the FM band 87.5–108 MHz in 100 kHz steps and logs RSSI.
    radio.set_volume(0);                                                   // Mute for scanning, (volume=0) → void
    radio.set_mute(true);                                                  // Mute output, (mute=true) → void
    printf("freq_kHz,rssi,stereo\n");
    for (uint32_t f = 87500; f <= 108000; f += 100) {
        radio.set_frequency(f);                                            // Tune to frequency, (kHz) → void
        usleep(50000);
        int rssi = radio.rssi();                                           // Read RSSI 0–127, () → int
        printf("%u,%d,%d\n", f, rssi, radio.is_stereo());                 // is_stereo() → bool
    }
    return 0;
}
