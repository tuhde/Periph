// Demo for the APDS-9930 — adaptive backlight + screen-lock scenario.

#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Apds9930.h"

#define DIM_LUX_THRESHOLD 10.0f
#define PROX_SCREEN_OFF  400

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x39;
    I2CConnectionLinux connection(bus, addr);                                 // Create I2C connection, (bus, addr) → I2CConnectionLinux

    APDS9930Full apds(connection);                                              // Create APDS-9930 Full, (connection) → APDS9930Full
                                                                               // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive

    usleep(110 * 1000);
    // --- Sample lux and proximity once per second for 30 cycles ---
    // The user is encouraged to cover the sensor with a hand (proximity
    // rises) and to dim/undim the room light to watch both action lines
    // fire.
    for (int i = 0; i < 30; i++) {
        usleep(1000 * 1000);
        float lx = apds.lux();                                                  // Read ambient illuminance, () → float lx
                                                                               // IR-compensated lux via Ch0/Ch1 difference
        uint16_t p = apds.proximity();                                          // Read proximity count, () → uint16_t count
                                                                               // 16-bit ADC value; higher = closer
        printf("lux=%.1f lx  proximity=%u\n", lx, p);
        if (lx < DIM_LUX_THRESHOLD) {
            printf("  -> dim backlight\n");
        }
        if (p > PROX_SCREEN_OFF) {
            printf("  -> disable screen\n");
        }
    }
    return 0;
}