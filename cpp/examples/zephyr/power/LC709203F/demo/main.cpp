#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LC709203F.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define LC709203F_ADDR 0x0B

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LC709203F_ADDR);
    LC709203FFull gauge(connection, 1000, 1);                    // Create LC709203F driver, 1000 mAh 3.7 V LiPo

    printk("IC version 0x%04X, profile id 0x%04X, RSOC %d %%\n",
           gauge.ic_version(), gauge.profile_id(), gauge.rsoc());
    gauge.set_alarm_rsoc(20);                                   // Alarm at 20 % RSOC, (percent)
    gauge.set_alarm_voltage(3.5f);                              // Alarm at 3.5 V, (volts)

    // Battery monitor: one line per 5 s for a minute, host temperature fixed at 25 °C
    for (int n = 0; n < 12; n++) {
        gauge.set_temperature(25.0f);
        printk("%.3f V  %.1f %%  %.1f C  alarm=%d\n", (double)gauge.voltage(), (double)gauge.percent(),
               (double)gauge.temperature(), (int)gauge.pollInterrupt());
        k_msleep(5000);
    }

    gauge.sleep();
    gauge.wake();
    printk("sleeping: %d\n", (int)gauge.is_sleeping());
    return 0;
}
