#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711ConnectionZephyr.h"
#include "HX710B.h"

#define HX710B_DOUT_NODE DT_ALIAS(hx710b_dout)
#define HX710B_SCK_NODE  DT_ALIAS(hx710b_sck)

// Battery-powered load cell demo: tare at startup, then print weight
// continuously, watching the DVDD−AVDD supply-difference reading for
// drift that signals a low battery.
static const float SCALE_FACTOR = 420.0f;
static const int32_t LOW_BATT_DELTA = 50000;

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX710B_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX710B_SCK_NODE,  gpios);

    HX711ConnectionZephyr connection(dout, pd_sck);
    HX710BFull<HX711ConnectionZephyr> chip(connection);

    printk("Taring — keep scale empty...\n");
    chip.tare(10);                                         // Capture zero offset from 10-reading average, (times=10) → void
    chip.set_scale(SCALE_FACTOR);                          // Set calibration scale factor, (factor: float) → void
    printk("Tare done. Place weight on scale.\n");

    // Capture the supply-difference baseline at full charge — uncalibrated
    // ADC code useful only for relative drift tracking against a known-good
    // baseline, not an absolute voltage reading.
    int32_t baseline_supp_diff = chip.read_supply_diff_raw();              // Read raw DVDD−AVDD supply-difference code, () → int32_t

    float prev_weight = -999999.0f;
    uint16_t iteration = 0;
    while (1) {
        float weight = chip.read_weight(3);                // Return calibrated weight, (times=3) → float
        float rounded = (float)((int)(weight * 10.0f + 0.5f)) / 10.0f;
        if (rounded - prev_weight > 1.0f || prev_weight - rounded > 1.0f) {
            printk("-> %.1f g\n", (double)rounded);
            prev_weight = rounded;
        }

        if (iteration % 20 == 0) {
            // Sample the DVDD−AVDD supply-difference channel every ~10 s:
            // uncalibrated ADC code for relative drift tracking against a
            // known-good baseline (the datasheet's stated purpose for this
            // channel in battery-powered weigh-scale applications).
            int32_t supp_diff = chip.read_supply_diff_raw();
            int32_t drift = supp_diff - baseline_supp_diff;
            if (drift > LOW_BATT_DELTA || drift < -LOW_BATT_DELTA) {
                printk("LOW BATTERY (supply_diff=%d, drift=%d)\n", (int)supp_diff, (int)drift);
            }
        }
        iteration++;

        k_sleep(K_MSEC(500));
    }
    return 0;
}
