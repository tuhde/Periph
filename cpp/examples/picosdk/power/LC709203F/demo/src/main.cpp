#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LC709203F.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    I2CConnectionPicoSDK connection(i2c0, 0x0B);
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
        sleep_ms(5000);
    }

    gauge.sleep();
    gauge.wake();
    printf("sleeping: %d\n", (int)gauge.is_sleeping());
    return 0;
}
