#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Max17048.h"

static volatile uint16_t g_alert = 0;
static void on_alert(uint16_t status) { g_alert = status; }

int main(int argc, char** argv) {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x36;
    I2CConnectionLinux connection(bus, addr);
    Max17048Full gauge(connection, 1);                      // Create MAX17048 driver, (connection, cells)

    printf("version 0x%04X, id 0x%02X\n", (unsigned)(gauge.version()), (unsigned)(gauge.chip_id()));
    gauge.clear_status(Max17048Full::SOURCE_RESET);         // Clear the reset indicator, (mask)

    // Battery fuel gauge: one line per second for 30 s
    for (int n = 0; n < 30; n++) {
        printf("%.3f V  %.1f %%  %+.1f %%/h\n", (double)(gauge.voltage()), (double)(gauge.percent()), (double)(gauge.charge_rate()));
        usleep(1000000);
    }

    gauge.set_alert_soc(10);                                // Low-SOC alert at 10 %, (percent)
    gauge.set_voltage_alert(3.3f, 4.25f);                   // Voltage window 3.3-4.25 V, (min_v, max_v)
    gauge.onInterrupt(on_alert);                            // Subscribe to ALRT, (callback, intPin = nullptr)
    usleep(5000000);
    gauge.offInterrupt();
    printf("poll_interrupt 0x%04X (callback saw 0x%04X)\n", (unsigned)(gauge.pollInterrupt()), (unsigned)g_alert);  // Without an ALRT pin, () → uint16_t

    gauge.compensate_temperature(5.0f);                     // RCOMP for the temperature, (celsius)
    printf("RCOMP at 5 C: 0x%02X\n", (unsigned)(gauge.get_rcomp()));
    gauge.compensate_temperature(35.0f);
    printf("RCOMP at 35 C: 0x%02X\n", (unsigned)(gauge.get_rcomp()));
    gauge.compensate_temperature(20.0f);

    gauge.disable_hibernate();                              // Never hibernate, ()
    printf("hibernating %d\n", (int)gauge.is_hibernating());
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Back to the defaults

    gauge.enable_sleep(true);                               // Sleep round trip, (enabled)
    gauge.sleep();
    usleep(1000000);
    gauge.wake();
    gauge.enable_sleep(false);
    printf("after wake: %.3f V\n", (double)(gauge.voltage()));
    if (argc > 1 && strcmp(argv[1], "--quick-start") == 0) {
        gauge.quick_start();                                    // Restart the SOC estimate, ()
        usleep(1000000);
        printf("SOC after quick start: %.1f %%\n", (double)gauge.percent());
    }
    return 0;
}
