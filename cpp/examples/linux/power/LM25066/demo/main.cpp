#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LM25066.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);
    LM25066Full hotswap(connection, 1.0f, false);      // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    char id[16], mdl[16], rev[16];
    hotswap.manufacturer_id(id, sizeof id); hotswap.model(mdl, sizeof mdl); hotswap.revision(rev, sizeof rev); // Identify the chip, (buf, len)
    printf("%s %s %s\n", id, mdl, rev);
    hotswap.clear_faults();                                  // Release SMBA after power-up, ()
    // Hot-swap rail monitor: one line per second for a minute
    for (int i = 0; i < 60; i++) {
        if (i == 30) {
            hotswap.set_averaging(6);                        // 64-sample averaging, (exponent)
            hotswap.set_vin_uv_warn(10.0f);                  // VIN under-voltage warning at 10 V, (volts)
            hotswap.set_iin_warn(20.0f);                     // Input over-current warning at 20 A, (amps)
            hotswap.enable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN | LM25066Full::SOURCE_IIN_WARN); // Unmask both alerts, (source)
        }
        printf("VIN %.2f V  VOUT %.2f V  IIN %.3f A (avg %.3f)  PIN %.2f W  T %.1f C\n",
               (double)hotswap.voltage_in(), (double)hotswap.voltage_out(), (double)hotswap.current(),
               (double)hotswap.current_avg(), (double)hotswap.power(), (double)hotswap.temperature());
        usleep(1000 * 1000);
    }
    printf("peak power %.2f W\n", (double)hotswap.power_peak()); // Peak input power, () → float W
    printf("diagnostic 0x%04X\n", (unsigned)hotswap.pollInterrupt()); // Alert flags, () → uint16_t
    hotswap.clear_faults();                                  // Finish servicing, ()
    // hotswap.operation_off();   // CUTS THE LOAD RAIL - only with explicit opt-in
    // hotswap.operation_on();
    return 0;
}
