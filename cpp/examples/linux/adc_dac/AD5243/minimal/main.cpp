#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AD5243.h"

int main() {
    I2CConnectionLinux connection(1, AD5243Minimal::I2C_ADDRESS);
    AD5243Minimal pot(connection);                      // Create AD5243 driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    static const int codes[] = {0, 64, 128, 192, 255};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(0, codes[i]);                      // Set wiper position, (channel 0–1, code 0–255) → bool
        uint8_t w = pot.getWiper(0);                    // Read wiper position, (channel 0–1) → uint8_t 0–255
        float r = pot.getResistance(0);                 // Read W–B resistance, (channel 0–1) → float Ω
        printf("code %d read %d -> %.1f ohm\n", codes[i], (int)w, (double)r);
        usleep(500UL * 1000UL);
    }
    pot.setResistance(1, 2200.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    return 0;
}
