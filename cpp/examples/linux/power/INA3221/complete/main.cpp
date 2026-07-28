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

    printf("mfr=0x%04X die=0x%04X\n",
           ina.manufacturer_id(), ina.die_id());                           // Read manufacturer/die ID, () → uint16_t
    for (int ch = 1; ch <= 3; ch++)
        printf("ch%d V=%.4f I=%.4f\n",
               ch, ina.voltage(ch), ina.current(ch));
    ina.configure(7, 4, 4, 7);                                            // Configure averages+times+mode, (avg, vbus_ct, vsh_ct, mode) → void
    ina.set_channel_enable(1, true);                                       // Enable/disable channel, (ch=1-3, enable) → void
    ina.set_channel_enable(2, false);
    ina.set_channel_enable(3, true);
    ina.set_warning_alert(1, 1.5f);                                        // Set per-channel warning threshold, (ch, amperes) → void
    printf("flags=0x%04X\n", ina.mask_enable());                          // Read MASK/ENABLE register, () → uint16_t
    ina.reset();                                                           // Trigger software reset, () → void
    return 0;
}
