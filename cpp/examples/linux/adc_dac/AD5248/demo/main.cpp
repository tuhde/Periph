#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AD5248.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x2C;
    I2CConnectionLinux connection(bus, addr);

    AD5248Full pot(connection, 10000.0f, 160.0f);                // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

    // --- Sweep channel 0 across its full range ---
    // Programmable gain / level trim: step the wiper from code 0 to 255 in steps of 16
    // and print the resulting W-B resistance. Zero scale is not 0 Ω (about 2*RW = 320 Ω).
    for (int code = 0; code <= 255; code += 16) {
        pot.set_wiper(0, code);                              // Set channel 0 wiper, (channel 0-1, code 0-255) → void
        float ohms = pot.get_resistance(0);                  // Read W-B resistance, (channel 0-1) → float Ω
        printf("code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        usleep(100000);
    }
    // --- Set channel 1 to 2.2 kΩ and report the quantisation error ---
    // 256 positions over 10 kΩ give a step of about 39 Ω, so the result is the nearest code.
    pot.set_resistance(1, 2200.0f);                          // Set channel 1 to 2.2 kΩ, (channel 0-1, ohms) → void
    float actual = pot.get_resistance(1);                    // Read back W-B resistance, (channel 0-1) → float Ω
    printf("ch1 actual ohm=%.1f error ohm=%.1f\n", (double)(actual), (double)(actual - 2200.0f));
    // --- Shutdown and resume channel 1 ---
    // Shutdown opens terminal A and shorts the wiper to B (about 0 Ω) without changing the RDAC.
    pot.shutdown(1);                                         // Shut channel 1 down, (channel 0-1) → void
    bool down = pot.is_shutdown(1);                          // Query driver-tracked state, (channel 0-1) → bool
    printf("ch1 shutdown=%d\n", (int)(down));
    usleep(1000000);
    pot.resume(1);                                           // Resume channel 1, (channel 0-1) → void
    // --- Restore the power-up preset ---
    pot.reset();                                             // Reset both channels to midscale, () → void
    return 0;
}
