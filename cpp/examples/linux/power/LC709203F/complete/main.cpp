#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LC709203F.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x0B;
    I2CConnectionLinux connection(bus, addr);
    LC709203FFull gauge(connection, 1000, 1);                    // Create LC709203F driver, (connection, capacityMah, profile)

    printf("%.3f\n", (double)gauge.voltage());                  // Cell voltage, () → float V
    printf("%.1f\n", (double)gauge.percent());                  // State of charge (ITE), () → float %
    printf("%.2f\n", (double)gauge.temperature());              // Cell temperature, () → float °C
    printf("%d\n", (int)gauge.rsoc());                          // RSOC register, () → uint8_t %
    printf("%d\n", (int)gauge.profile_id());                    // Number of the parameter, () → uint16_t
    printf("%d\n", (int)gauge.ic_version());                    // IC version, () → uint16_t
    printf("%d\n", (int)gauge.get_profile());                   // Battery profile, () → uint8_t
    printf("%d\n", (int)gauge.get_apa());                       // Adjustment Pack Application, () → uint8_t
    printf("%d\n", (int)gauge.get_apt());                       // Adjustment Pack Thermistor, () → uint16_t
    printf("%d\n", (int)gauge.get_b_constant());                // Thermistor B constant, () → uint16_t K
    printf("%d\n", (int)gauge.get_current_direction());         // Current direction, () → uint16_t
    printf("%d\n", (int)gauge.is_thermistor_mode());            // Temperature source, () → bool
    printf("%d\n", (int)gauge.get_alarm_rsoc());                // Low-RSOC alarm threshold, () → uint8_t %
    printf("%.3f\n", (double)gauge.get_alarm_voltage());        // Low-voltage alarm threshold, () → float V
    printf("%d\n", (int)gauge.pollInterrupt());                 // Alarm bitmask, () → uint8_t (ALARM_RSOC = 1, ALARM_VOLTAGE = 2)
    gauge.set_temperature(25.0f);                               // Host temperature (I²C mode), (celsius) clamped to -20...+60 °C
    gauge.set_rsoc(50);                                         // Force RSOC, (percent)
    gauge.init_rsoc();                                          // Initial RSOC from present voltage, ()
    gauge.before_rsoc();                                        // RSOC from max voltage since reset, ()
    gauge.use_thermistor(3380);                                 // Thermistor mode, (bConstant)
    gauge.use_i2c_temperature(25.0f);                           // Back to I²C temperature mode, (celsius)
    gauge.set_apa(0x19);                                        // Adjustment Pack Application, (value)
    gauge.set_apt(0x1E);                                        // Adjustment Pack Thermistor, (value)
    gauge.set_current_direction(LC709203FFull::DIRECTION_AUTO); // Current direction, (mode) AUTO / CHARGE / DISCHARGE
    gauge.set_profile(1);                                       // Battery profile 0/1, (profile) — re-initializes the chip (90 ms)
    gauge.set_alarm_rsoc(20);                                   // Low-RSOC alarm, (percent) 0 = off
    gauge.set_alarm_voltage(3.5f);                              // Low-voltage alarm, (volts) 0 = off
    gauge.sleep();                                              // Sleep mode, ()
    gauge.wake();                                               // Wake up, ()
    printf("%d\n", (int)gauge.is_sleeping());                   // Power mode, () → bool
    return 0;
}
