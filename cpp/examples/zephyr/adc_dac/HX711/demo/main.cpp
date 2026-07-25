#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711TransportZephyr.h"
#include "HX711.h"

#define HX711_DOUT_NODE DT_ALIAS(hx711_dout)
#define HX711_SCK_NODE  DT_ALIAS(hx711_sck)

static const float SCALE_FACTOR = 420.0f;

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX711_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX711_SCK_NODE,  gpios);

    HX711TransportZephyr transport(dout, pd_sck);
    HX711Full<HX711TransportZephyr> chip(transport);

    printk("Taring — keep scale empty...\n");
    chip.tare(10);                                         // Capture zero offset from 10-reading average, (times=10) → void
    chip.set_scale(SCALE_FACTOR);                          // Set calibration scale factor, (factor: float) → void
    printk("Tare done. Place weight on scale.\n");

    float prev_weight = -999999.0f;
    while (1) {
        float weight = chip.read_weight(3);                // Return calibrated weight, (times=3) → float
        float rounded = (float)((int)(weight * 10.0f + 0.5f)) / 10.0f;
        if (rounded - prev_weight > 1.0f || prev_weight - rounded > 1.0f) {
            printk("-> %.1f g\n", (double)rounded);
            prev_weight = rounded;
        }
        k_sleep(K_MSEC(500));
    }
    return 0;
}
