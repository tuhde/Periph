#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8591.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x48;
    I2CConnectionLinux connection(bus, addr);

    PCF8591Full pcf(connection);                                            // Create PCF8591 driver, (connection)

    // --- Analog data logger ---
    // Reads all 4 ADC channels at 2 Hz and echoes ch0 back to the DAC.
    while (true) {
        uint8_t buf[4];
        pcf.read_all(buf);                                                 // Read all 4 ADC channels, (out[4]) → void
        pcf.dac_write(buf[0]);                                             // Echo ch0 to DAC output, (value 0–255) → void
        printf("ch0=%u ch1=%u ch2=%u ch3=%u\n",
               buf[0], buf[1], buf[2], buf[3]);
        usleep(500000);
    }
    return 0;
}
