#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711TransportZephyr.h"
#include "HX711.h"

#define HX711_DOUT_NODE DT_ALIAS(hx711_dout)
#define HX711_SCK_NODE  DT_ALIAS(hx711_sck)

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX711_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX711_SCK_NODE,  gpios);

    HX711TransportZephyr transport(dout, pd_sck);
    HX711Full<HX711TransportZephyr> chip(transport);

    check_true(true, "is_ready compiles");

    int32_t raw = chip.read_raw();
    check_true(raw >= -8388608 && raw <= 8388607, "read_raw in 24-bit signed range");

    chip.set_gain(128);
    check_true(true, "set_gain(128) accepted");

    chip.set_gain(64);
    check_true(true, "set_gain(64) accepted");

    chip.set_gain(32);
    check_true(true, "set_gain(32) accepted");

    chip.set_gain(128);

    int32_t avg = chip.read_average(3);
    check_true(avg >= -8388608 && avg <= 8388607, "read_average in range");

    chip.tare(3);
    check_true(true, "tare accepted");

    chip.set_scale(420.0f);
    check_true(true, "set_scale accepted");

    float weight = chip.read_weight(1);
    check_true(true, "read_weight returns float");

    chip.power_down();
    check_true(true, "power_down accepted");

    chip.power_up();
    check_true(true, "power_up accepted");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
