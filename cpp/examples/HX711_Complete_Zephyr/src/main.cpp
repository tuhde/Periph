#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711TransportZephyr.h"
#include "HX711.h"

#define HX711_DOUT_NODE DT_ALIAS(hx711_dout)
#define HX711_SCK_NODE  DT_ALIAS(hx711_sck)

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX711_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX711_SCK_NODE,  gpios);

    HX711TransportZephyr transport(dout, pd_sck);
    HX711Full<HX711TransportZephyr> chip(transport);

    while (1) {
        bool ready = chip.is_ready();                      // Check if conversion is ready (non-blocking), () → bool
        int32_t raw = chip.read_raw();                     // Read signed 24-bit ADC value at current gain, () → int32_t

        chip.set_gain(64);                                 // Select channel and gain, (gain: 128|64|32) → void
        chip.set_gain(128);

        int32_t avg = chip.read_average(10);               // Average multiple raw readings, (times=10) → int32_t

        chip.tare(10);                                     // Capture zero offset from 10-reading average, (times=10) → void
        int32_t offset = chip.get_offset();                // Return stored tare offset, () → int32_t

        chip.set_scale(420.0f);                            // Set calibration scale factor, (factor: float) → void
        float scale = chip.get_scale();                    // Return current scale factor, () → float

        float weight = chip.read_weight(5);                // Return calibrated weight, (times=1) → float
        printk("weight=%.1f g\n", (double)weight);

        chip.power_down();                                 // Enter power-down mode, () → void
        chip.power_up();                                   // Exit power-down, reset chip, discard settling conversion, () → void

        k_sleep(K_MSEC(500));
    }
    return 0;
}
