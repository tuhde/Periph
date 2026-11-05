#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8578.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x3C;
    I2CConnectionLinux connection(bus, addr);

    PCF8578Minimal lcd(connection, 16);                       // Create PCF8578 driver, (connection, rows=16 8/16/24/32)

    lcd.clear();                                              // Clear display RAM, () → void
    lcd.set_pixel(0, 0);                                      // Set one pixel, (x 0–23, y 0–15, on=true) → void
    lcd.set_pixel(23, 15);                                    // Set opposite corner, (x 0–23, y 0–15, on=true) → void
    static const uint8_t frame[] = {0xFF, 0x81, 0x81, 0xFF};
    lcd.write_columns(4, 0, frame, sizeof(frame));            // Write column bytes, (x 0–23, bank 0–1, data, len) → bool

    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
