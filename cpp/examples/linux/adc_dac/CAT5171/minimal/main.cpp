#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "CAT5171.h"

int main() {
    I2CConnectionLinux connection(1, CAT5171Minimal::I2C_ADDRESS);
    CAT5171Minimal pot(connection);                     // Create CAT5171 driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    static const int codes[] = {0, 64, 128, 192, 255};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–255) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–255
        float r = pot.getResistance();                  // Read W–B resistance, () → float Ω
        printf("code %d read %d -> %.1f ohm\n", codes[i], (int)w, (double)r);
        usleep(500UL * 1000UL);
    }
    pot.setResistance(22000.0f);                        // Set W–B resistance, (ohms Ω) → void
    return 0;
}
