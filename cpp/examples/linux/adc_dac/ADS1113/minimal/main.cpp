#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS1113.h"

int main() {
    I2CConnectionLinux connection(1, ADS1113Minimal::I2C_ADDRESS);
    ADS1113Minimal adc(connection);                         // Create ADS1113 driver, (connection)

    while (1) {
        float v = adc.readVoltage();                        // Read voltage, () → float V
        printf("%.5f V\n", (double)v);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
