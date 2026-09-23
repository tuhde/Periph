#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP9808.h"

int main() {
    I2CConnectionLinux connection(1, MCP9808Minimal::I2C_ADDRESS);
    MCP9808Minimal sensor(connection);                      // Create MCP9808 driver, (connection)

    while (1) {
        float t = sensor.readTemperature();                     // Read ambient temperature, () → °C
        printf("%.4f C\n", (double)t);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
