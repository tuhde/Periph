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
           ina.manufacturer_id(), ina.die_id());                           // Manufacturer/die ID, () → uint16_t (0x5449 / 0x3220)
    for (int ch = 1; ch <= 3; ch++)
        printf("ch%d V=%.4f V shunt=%.6f V I=%.4f A P=%.4f W\n", ch,
               (double)ina.voltage(ch), (double)ina.shunt_voltage(ch),     // Bus/shunt voltage, (channel 1–3) → float V
               (double)ina.current(ch), (double)ina.power(ch));            // Current, (channel) → float A ; power, (channel) → float W

    ina.configure(7, 4, 4, INA3221Full::MODE_SHUNT_BUS_CONT);              // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode) → void
                                                                           // 1024-sample averaging, 1.1 ms conversion times
    ina.enable_channel(2, false);                                          // Enable/disable a channel, (channel 1–3, enabled) → void
    printf("ch2 enabled=%d ready=%d\n", ina.channel_enabled(2),           // Channel enabled?, (channel) → bool
           ina.conversion_ready());                                        // Conversion-ready flag, () → bool
    ina.enable_channel(2, true);

    ina.set_critical_alert(1, 0.080f);                                     // Critical alert on shunt voltage, (channel, limit_v V, latch=false) → void
                                                                           // compared per conversion
    ina.set_warning_alert(1, 0.050f);                                      // Warning alert on averaged shunt voltage, (channel, limit_v V, latch=false) → void
    const uint8_t sum_channels[2] = {1, 2};
    ina.set_summation_channels(sum_channels, 2, 0.100f);                   // Sum shunt voltages, (channels, n, limit_v V) → void
    printf("sum=%.6f V\n", (double)ina.summation_value());               // Summed shunt voltage, () → float V
    ina.set_power_valid_limits(5.25f, 4.75f);                              // Power-valid window, (upper_v V, lower_v V) → void
    printf("power_valid=%d flags=0x%04X\n", ina.power_valid(),            // All bus voltages in window?, () → bool
           ina.alert_flags());                                             // MASK/ENABLE flags, () → uint16_t (CF1…CVRF)

    ina.shutdown();                                                        // Power down, () → void
    ina.wake();                                                            // Restore previous mode, () → void
    ina.reset();                                                           // Software reset, () → void
    return 0;
}
