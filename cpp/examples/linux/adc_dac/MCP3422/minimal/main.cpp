#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP3422.h"

int main() {
    I2CConnectionLinux connection(1, MCP3422Minimal::I2C_ADDRESS);
    MCP3422Minimal adc(connection);                     // Create MCP3422 driver, (connection)
    for (int i = 0; i < 10; i++) {
        int32_t raw0 = adc.readRaw(0);                  // Read a signed code, (channel 0–1) → int32_t
        float v1 = adc.readVoltage(1);                  // Read the differential voltage, (channel 0–1) → float V
        printf("CH1 raw %d, CH2 %.6f V\n", (int)raw0, (double)v1);
        float both[2];
        bool ok = adc.readAll(both);                    // Read both channels, (float out[2]) → bool
        printf("all %d %.6f %.6f\n", (int)ok, (double)both[0], (double)both[1]);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
