#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "APDS9960.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x39;
    I2CConnectionLinux connection(bus, addr);

    APDS9960Full apds(connection);                                          // Create APDS9960 driver, (connection)

    uint8_t id = apds.device_id();                                         // Read device ID, () → uint8_t  (0xAB)
    printf("device_id=0x%02X\n", id);
    apds.set_adc_integration_time(0xB6);                                   // Set RGBC integration time, (value 0x00–0xFF) → void
    apds.set_adc_gain(0);                                                  // Set RGBC gain, (0–3 → 1×/4×/16×/64×) → void
    uint16_t r, g, b, c;
    apds.read_color(r, g, b, c);                                           // Read RGBC channels, (r, g, b, clear) → void
    printf("r=%u g=%u b=%u c=%u\n", r, g, b, c);
    apds.enable_proximity(true);                                           // Enable proximity engine, (enable) → void
    usleep(50000);
    printf("prox=%u\n", apds.read_proximity());                           // Read proximity value 0–255, () → uint8_t
    apds.enable_gesture(true);                                             // Enable gesture engine, (enable) → void
    apds.enable_proximity(false);                                          // Disable proximity, (enable=false) → void
    apds.enable_gesture(false);                                            // Disable gesture, (enable=false) → void
    return 0;
}
