#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "DS3502.h"

int main() {
    I2CConnectionLinux connection(1, DS3502Minimal::I2C_ADDRESS);
    DS3502Minimal pot(connection, 15.0f, 0.0f);         // Create DS3502 driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    static const int codes[] = {0, 32, 64, 96, 127};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–127) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–127
        float v = pot.getVoltage();                     // Read unloaded RW voltage, () → float V
        printf("code %d read %d -> %.3f V\n", codes[i], (int)w, (double)v);
        usleep(500UL * 1000UL);
    }
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    return 0;
}
