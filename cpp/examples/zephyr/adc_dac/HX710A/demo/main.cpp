#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711ConnectionZephyr.h"
#include "HX710A.h"

#define HX710A_DOUT_NODE DT_ALIAS(hx710a_dout)
#define HX710A_SCK_NODE  DT_ALIAS(hx710a_sck)

// Temperature-monitored load cell demo: tare at startup, then print weight
// continuously, sampling the on-chip temperature sensor periodically.
static const float SCALE_FACTOR = 420.0f;

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX710A_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX710A_SCK_NODE,  gpios);

    HX711ConnectionZephyr connection(dout, pd_sck);
    HX710AFull<HX711ConnectionZephyr> chip(connection);

    printk("Taring — keep scale empty...\n");
    chip.tare(10);                                         // Capture zero offset from 10-reading average, (times=10) → void
    chip.set_scale(SCALE_FACTOR);                          // Set calibration scale factor, (factor: float) → void
    printk("Tare done. Place weight on scale.\n");

    float prev_weight = -999999.0f;
    uint16_t iteration = 0;
    while (1) {
        float weight = chip.read_weight(3);                // Return calibrated weight, (times=3) → float
        float rounded = (float)((int)(weight * 10.0f + 0.5f)) / 10.0f;
        if (rounded - prev_weight > 1.0f || prev_weight - rounded > 1.0f) {
            printk("-> %.1f g\n", (double)rounded);
            prev_weight = rounded;
        }

        if (iteration % 10 == 0) {
            // Sample the on-chip temperature sensor every ~5 s: an
            // uncalibrated raw ADC code for relative drift compensation,
            // not an absolute °C measurement.
            int32_t temp_raw = chip.read_temperature_raw();
            printk("temp raw=%d\n", (int)temp_raw);
        }
        iteration++;

        k_sleep(K_MSEC(500));
    }
    return 0;
}
