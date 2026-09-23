#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "TMP117.h"

int main() {
    I2CConnectionLinux connection(1, TMP117Minimal::I2C_ADDRESS);
    TMP117Minimal sensor(connection);                       // Create TMP117 driver, (connection)

    while (1) {
        float t = sensor.readTemperature();                     // Read temperature, () → °C
        printf("%.4f C\n", (double)t);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
