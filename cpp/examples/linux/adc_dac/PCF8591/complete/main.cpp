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

    PCF8591Full pcf(transport);                                            // Create PCF8591 driver, (transport)

    for (int ch = 0; ch < 4; ch++)
        printf("ch%d=%u\n", ch, pcf.read_channel(ch));                    // Read ADC channel 0–3, (ch) → uint8_t
    pcf.dac_write(128);                                                    // Write DAC output, (value 0–255) → void
    pcf.set_auto_increment(true);                                          // Enable auto-increment, (enable) → void
    uint8_t buf[4];
    pcf.read_all(buf);                                                     // Read all 4 ADC channels, (out[4]) → void
    printf("all: %u %u %u %u\n", buf[0], buf[1], buf[2], buf[3]);
    pcf.set_auto_increment(false);                                         // Disable auto-increment, (enable=false) → void
    return 0;
}
