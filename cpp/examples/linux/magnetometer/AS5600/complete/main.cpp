#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AS5600.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x36;
    I2CConnectionLinux connection(bus, addr);

    AS5600Full as(connection);                                              // Create AS5600 driver, (connection)

    uint8_t status = as.status();                                          // Read STATUS register, () → uint8_t  (bits: MH MD ML)
    printf("status=0x%02X\n", status);
    printf("magnitude=%u\n", as.magnitude());                             // Read AGC magnitude, () → uint16_t
    printf("raw=%u\n", as.raw_angle());                                   // Read raw 12-bit angle, () → uint16_t
    printf("angle=%.2f °\n", as.angle());                                 // Read angle, () → float °
    as.set_start_position(0);                                              // Set ZPOS (start angle), (raw 0–4095) → void
    as.set_stop_position(4095);                                            // Set MPOS (stop angle), (raw 0–4095) → void
    as.set_max_angle(4095);                                                // Set MANG, (raw 0–4095) → void
    as.configure(0x00);                                                    // Write CONF register, (value) → void
    return 0;
}
