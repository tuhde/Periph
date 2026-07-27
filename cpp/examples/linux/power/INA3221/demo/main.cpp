#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "INA3221.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    INA3221Full ina(connection);                                            // Create INA3221 driver, (connection)

    // --- Triple-rail power audit ---
    // Labels three rails (3V3, 5V, 12V) and logs their current draw.
    const char* labels[] = {"3V3", "5V ", "12V"};
    ina.configure(7, 4, 4, 7);                                            // Configure ADC, (avg=128, 1.1ms, 1.1ms, continuous) → void
    while (true) {
        for (int ch = 1; ch <= 3; ch++)
            printf("%s: %.4f V  %.4f A\n",
                   labels[ch-1], ina.voltage(ch), ina.current(ch));       // Read bus voltage/current, (ch=1-3) → float
        printf("---\n");
        usleep(1000000);
    }
    return 0;
}
