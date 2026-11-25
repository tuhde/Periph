#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "TMP102.h"

int main() {
    I2CConnectionLinux connection(1, TMP102Minimal::I2C_ADDRESS);
    TMP102Minimal sensor(connection);                       // Create TMP102 driver, (connection)

    while (1) {
        float t = sensor.readTemperature();                     // Read temperature, () → °C
        printf("%.4f C\n", (double)t);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
