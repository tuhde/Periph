#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LM25066.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);
    LM25066Minimal hotswap(connection, 1.0f, false);   // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    while (true) {
        printf("VIN %.3f V  VOUT %.3f V\n", (double)hotswap.voltage_in(), (double)hotswap.voltage_out()); // Read VIN / VOUT, () → float V each
        printf("IIN %.3f A  PIN %.2f W  T %.1f C\n", (double)hotswap.current(), (double)hotswap.power(), (double)hotswap.temperature()); // Read A / W / MOSFET °C, () → float each
        usleep(1000 * 1000);
    }
    return 0;
}
