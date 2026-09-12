#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711ConnectionZephyr.h"
#include "HX710A.h"

#define HX710A_DOUT_NODE DT_ALIAS(hx710a_dout)
#define HX710A_SCK_NODE  DT_ALIAS(hx710a_sck)

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX710A_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX710A_SCK_NODE,  gpios);

    HX711ConnectionZephyr connection(dout, pd_sck);
    HX710AFull<HX711ConnectionZephyr> chip(connection);

    while (1) {
        bool ready = chip.is_ready();                      // Check if conversion is ready (non-blocking), () → bool
        int32_t raw = chip.read_raw();                     // Read signed 24-bit differential-input value, () → int32_t

        chip.set_rate(40);                                 // Select differential-input output rate, (rate: 10|40) → void
        chip.set_rate(10);

        int32_t avg = chip.read_average(10);               // Average multiple raw readings, (times=10) → int32_t

        chip.tare(10);                                     // Capture zero offset from 10-reading average, (times=10) → void
        int32_t offset = chip.get_offset();                // Return stored tare offset, () → int32_t

        chip.set_scale(420.0f);                            // Set calibration scale factor, (factor: float) → void
        float scale = chip.get_scale();                    // Return current scale factor, () → float

        float weight = chip.read_weight(5);                // Return calibrated weight, (times=1) → float
        printk("weight=%.1f g\n", (double)weight);

        int32_t temp_raw = chip.read_temperature_raw();    // Read raw on-chip temperature code, () → int32_t
        printk("temp raw=%d\n", (int)temp_raw);

        chip.power_down();                                 // Enter power-down mode, () → void
        chip.power_up();                                   // Exit power-down, reset chip, discard settling conversion, () → void

        k_sleep(K_MSEC(500));
    }
    return 0;
}
