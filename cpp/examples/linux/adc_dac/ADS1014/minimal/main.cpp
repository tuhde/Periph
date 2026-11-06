#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS1014.h"

int main() {
    I2CConnectionLinux connection(1, ADS1014Minimal::I2C_ADDRESS);
    ADS1014Minimal adc(connection);                     // Create ADS1014 driver, (connection)

    while (1) {
        int16_t raw = adc.readRaw();                    // Read raw code, () → int16 (−2048…2047)
        float v = adc.readVoltage();                    // Read voltage, () → V
        printf("%d -> %.3f V\n", (int)raw, (double)v);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
