#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "INA219.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    INA219Full ina(connection, 0.1f, 2.0f);                                 // Create INA219 driver, (connection, r_shunt=0.1 Ω, max_current=2.0 A)
                                                                           // calibration register derived from shunt and maximum current

    printf("V=%.3f V I=%.4f A P=%.4f W\n",
           (double)ina.voltage(), (double)ina.current(), (double)ina.power());  // Bus voltage, () → float V ; current, () → float A ; power, () → float W
    printf("shunt=%.6f V\n", (double)ina.shunt_voltage());                // Shunt voltage, () → float V

    ina.configure(INA219Full::BRNG_16V, INA219Full::PGA_1,
                  INA219Full::ADC_12BIT, INA219Full::ADC_AVG_16,
                  INA219Full::MODE_SHUNT_BUS_CONT);                        // Configure ADC, (brng, pga, badc, sadc, mode) → void
                                                                           // 16 V range, ±40 mV shunt range, 16-sample shunt averaging
    printf("ready=%d overflow=%d\n", ina.conversion_ready(), ina.overflow());  // Conversion-ready flag, () → bool ; math overflow flag, () → bool

    ina.configure(INA219Full::BRNG_32V, INA219Full::PGA_8, INA219Full::ADC_12BIT,
                  INA219Full::ADC_12BIT, INA219Full::MODE_SHUNT_BUS_TRIG);
    ina.trigger();                                                         // Start a single-shot conversion, () → void
                                                                           // only meaningful in a triggered mode
    usleep(2000);
    printf("triggered V=%.3f V\n", (double)ina.voltage());

    ina.shutdown();                                                        // Enter power-down, () → void
    ina.wake();                                                            // Restore the previous mode, () → void
    ina.reset();                                                           // Power-on reset, () → void
                                                                           // re-applies the default configuration and calibration
    return 0;
}
