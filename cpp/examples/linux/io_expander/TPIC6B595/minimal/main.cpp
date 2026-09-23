#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "SiPoConnectionLinux.h"
#include "TPIC6B595.h"

int main() {
    const char* chip_path = getenv("GPIO_CHIP");
    if (!chip_path) chip_path = "/dev/gpiochip0";

    int rck    = getenv("SIPO_RCK")    ? atoi(getenv("SIPO_RCK"))    : 5;
    int srclr  = getenv("SIPO_SRCLR")  ? atoi(getenv("SIPO_SRCLR"))  : 6;
    int g      = getenv("SIPO_G")      ? atoi(getenv("SIPO_G"))      : 13;
    int ser_in = getenv("SIPO_SER_IN") ? atoi(getenv("SIPO_SER_IN")) : 19;
    int srck   = getenv("SIPO_SRCK")   ? atoi(getenv("SIPO_SRCK"))   : 26;


    SiPoConnectionLinux connection(chip_path, ser_in, srck, rck, srclr, g); // Create bit-bang SiPo connection, (chip_path, ser_in, srck, rck, srclr=-1, g=-1)
    TPIC6B595Minimal<SiPoConnectionLinux> chip(connection);                                // Create TPIC6B595 driver, (connection, num_devices=1)
                                                                                            // initialises every output to OFF (shadow zeroed, latched once)

    auto p0 = chip.pin(0);                                                                  // Get pin proxy, (n=0) → IOExpanderPin
    auto p7 = chip.pin(7);                                                                  // Get pin proxy, (n=7) → IOExpanderPin

    while (true) {
        p0.high();                                                                          // Set DMOS output ON, () → void
        p7.low();                                                                           // Set DMOS output OFF, () → void
        usleep(500000);
        p0.low();                                                                           // Set DMOS output OFF, () → void
        p7.high();                                                                          // Set DMOS output ON, () → void
        usleep(500000);
    }
    return 0;
}
