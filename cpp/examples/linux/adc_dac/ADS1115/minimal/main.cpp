#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS1115.h"

int main() {
    I2CConnectionLinux connection(1, ADS1115Minimal::I2C_ADDRESS);
    ADS1115Minimal adc(connection);                         // Create ADS1115 driver, (connection)

    while (1) {
        float v = adc.readVoltage(0);                       // Read voltage, (channel=0) → float V
        printf("%.5f V\n", (double)v);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
