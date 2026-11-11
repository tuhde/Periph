#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS7828.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x48;
    I2CConnectionLinux connection(bus, addr);

    ADS7828Minimal adc(connection);                         // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    while (true) {
        uint16_t raw = adc.readRaw(0);                              // Read CH0 raw code, (channel=0–7) → uint16_t
        float v0 = adc.readVoltage(0);                              // Read CH0 voltage, (channel=0–7) → float V
        float all[8];
        adc.readAll(all);                                           // Read all eight channels, (out[8]) → void
        printf("raw=%u v0=%.3f V ch7=%.3f V\n", raw, (double)v0, (double)all[7]);
        usleep(500000);
    }
    return 0;
}
