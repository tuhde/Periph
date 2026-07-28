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

    // --- Rotary encoder with direction detection ---
    // Tracks angle changes and prints direction and speed.
    float prev = as.angle();                                               // Read angle, () → float °
    while (true) {
        usleep(50000);
        float curr = as.angle();                                           // Read angle, () → float °
        float delta = curr - prev;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        if (__builtin_fabsf(delta) > 0.5f)
            printf("%.2f °  %s  (Δ%.2f)\n", curr, delta > 0 ? "CW" : "CCW", delta);
        prev = curr;
    }
    return 0;
}
