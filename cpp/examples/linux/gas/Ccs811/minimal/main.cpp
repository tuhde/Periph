#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Ccs811.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x5A;
    I2CConnectionLinux connection(bus, addr);

    CCS811Minimal sensor(connection);                              // Create CCS811 driver, (connection) → void
    sensor.init();                                                 // Check HW_ID, start application, mode 1, () → bool
    while (true) {
        uint16_t eco2 = 0, tvoc = 0;
        bool ok = sensor.read(eco2, tvoc);                         // Wait for the next sample, (eco2_ppm&, tvoc_ppb&) → bool
        printf("eCO2 %u ppm TVOC %u ppb (ok=%u)\n", (unsigned)(eco2), (unsigned)(tvoc), (unsigned)(ok));
    }
    return 0;
}
