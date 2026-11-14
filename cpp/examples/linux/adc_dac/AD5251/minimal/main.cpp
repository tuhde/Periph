#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AD5251.h"

int main() {
    I2CConnectionLinux connection(1, AD5251Minimal::I2C_ADDRESS);
    AD5251Minimal pot(connection);                      // Create AD5251 driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω)
    static const int codes[] = {0, 16, 32, 48, 63};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(0, codes[i]);                      // Set wiper position, (channel 0–1, code 0–63) → bool
        uint8_t w = pot.getWiper(0);                    // Read wiper position, (channel 0–1) → uint8_t 0–63
        float r = pot.getResistance(0);                 // Read W–B resistance, (channel 0–1) → float Ω
        printf("code %d read %d -> %.1f ohm\n", codes[i], (int)w, (double)r);
        usleep(500UL * 1000UL);
    }
    pot.setResistance(1, 20000.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    return 0;
}
