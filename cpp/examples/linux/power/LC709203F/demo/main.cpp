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
    LC709203FFull gauge(connection, 1000, 1);                    // Create LC709203F driver, 1000 mAh 3.7 V LiPo

    printf("IC version 0x%04X, profile id 0x%04X, RSOC %d %%\n",
           gauge.ic_version(), gauge.profile_id(), gauge.rsoc());
    gauge.set_alarm_rsoc(20);                                   // Alarm at 20 % RSOC, (percent)
    gauge.set_alarm_voltage(3.5f);                              // Alarm at 3.5 V, (volts)

    // Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
    for (int n = 0; n < 12; n++) {
        gauge.set_temperature(25.0f);
        printf("%.3f V  %.1f %%  %.1f C  alarm=%d\n", (double)gauge.voltage(), (double)gauge.percent(),
               (double)gauge.temperature(), (int)gauge.pollInterrupt());
        usleep(5000000);
    }

    gauge.sleep();
    gauge.wake();
    printf("sleeping: %d\n", (int)gauge.is_sleeping());
    return 0;
}
