#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8576.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    PCF8576Minimal pcf(connection);                                         // Create PCF8576 driver, (connection)
                                                                           // 1:4 multiplex, 1/3 bias, display enabled

    const uint8_t digits[4] = {1, 2, 3, 4};
    for (uint8_t pos = 0; pos < 4; pos++)
        pcf.set_digit_7seg(pos, PCF8576Minimal::SEVEN_SEG[digits[pos]]);   // Write one 7-segment digit, (position, segments) → void
    usleep(2000000);
    pcf.clear();                                                           // Clear display RAM, () → void
    return 0;
}
