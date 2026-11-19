#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Pca9685.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    Pca9685Minimal pwm(connection);                   // Create PCA9685 driver, (connection)

    for (uint8_t step = 0; step <= 10; step++) {
        pwm.set_duty(0, step / 10.0f);                    // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → bool
        usleep((200) * 1000);
    }
    pwm.set_duty(0, 0.0f);

    return 0;
}
