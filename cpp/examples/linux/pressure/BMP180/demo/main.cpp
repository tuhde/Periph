#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "BMP180.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x77;
    I2CTransportLinux transport(bus, addr);

    BMP180Full bmp(transport);                                             // Create BMP180 driver, (transport)

    // --- Weather station with trend detection ---
    // Logs pressure every 60 s and notes rising/falling trend.
    float prev = bmp.pressure(3);                                          // Read pressure (high-res), (oss=3) → float Pa
    printf("%.2f Pa  (baseline)\n", prev);
    while (true) {
        usleep(60000000);
        float curr = bmp.pressure(3);                                      // Read pressure, (oss=3) → float Pa
        float delta = curr - prev;
        const char* trend = delta > 50 ? "RISING" : delta < -50 ? "FALLING" : "STEADY";
        printf("%.2f Pa  %s  (Δ%.2f)\n", curr, trend, delta);
        prev = curr;
    }
    return 0;
}
