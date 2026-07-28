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

    PCF8576Full pcf(connection);                                            // Create PCF8576 driver, (connection)

    pcf.set_display(true);                                                 // Enable display, (on=true) → void
    pcf.write_digits("8888");                                              // Write digit string, (str) → void
    usleep(1000000);
    pcf.set_blink(1);                                                      // Set blink mode, (mode 0=off 1=fast 2=slow) → void
    usleep(2000000);
    pcf.set_blink(0);                                                      // Set blink mode, (mode 0=off) → void
    pcf.write_raw(0, 0x7F);                                                // Write raw segment byte, (digit=0–3, segments) → void
    pcf.set_display(false);                                                // Disable display, (on=false) → void
    return 0;
}
