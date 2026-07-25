#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "PCF8591.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x48;
    I2CTransportLinux transport(bus, addr);

    PCF8591Minimal pcf(transport);                                         // Create PCF8591 driver, (transport)

    while (true) {
        for (int ch = 0; ch < 4; ch++)
            printf("ch%d=%u\n", ch, pcf.read_channel(ch));                // Read ADC channel, (ch=0–3) → uint8_t
        usleep(500000);
    }
    return 0;
}
