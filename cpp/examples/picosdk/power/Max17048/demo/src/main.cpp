#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Max17048.h"

static volatile uint16_t g_alert = 0;
static void on_alert(uint16_t status) { g_alert = status; }

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    I2CConnectionPicoSDK connection(i2c0, 0x36);
    Max17048Full gauge(connection, 1);                      // Create MAX17048 driver, (connection, cells)

    printf("version 0x%04X, id 0x%02X\n", (unsigned)(gauge.version()), (unsigned)(gauge.chip_id()));
    gauge.clear_status(Max17048Full::SOURCE_RESET);         // Clear the reset indicator, (mask)

    // Battery fuel gauge: one line per second for 30 s
    for (int n = 0; n < 30; n++) {
        printf("%.3f V  %.1f %%  %+.1f %%/h\n", (double)(gauge.voltage()), (double)(gauge.percent()), (double)(gauge.charge_rate()));
        sleep_ms(1000);
    }

    gauge.set_alert_soc(10);                                // Low-SOC alert at 10 %, (percent)
    gauge.set_voltage_alert(3.3f, 4.25f);                   // Voltage window 3.3-4.25 V, (min_v, max_v)
    gauge.onInterrupt(on_alert);                            // Subscribe to ALRT, (callback, intPin = nullptr)
    sleep_ms(5000);
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
    sleep_ms(1000);
    gauge.wake();
    gauge.enable_sleep(false);
    printf("after wake: %.3f V\n", (double)(gauge.voltage()));
    return 0;
}
