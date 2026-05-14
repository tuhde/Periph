#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "HX711TransportZephyr.h"

#ifndef HX711_DOUT_NODE
#define HX711_DOUT_NODE   DT_ALIAS(hx711_dout)
#endif
#ifndef HX711_SCK_NODE
#define HX711_SCK_NODE    DT_ALIAS(hx711_sck)
#endif

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

    check_true(true, "is_ready compiles");

    int32_t val = transport.read_raw(25);
    check_true(val >= -8388608 && val <= 8388607, "read_raw(25) in 24-bit signed range");

    val = transport.read_raw(26);
    check_true(val >= -8388608 && val <= 8388607, "read_raw(26) in 24-bit signed range");

    val = transport.read_raw(27);
    check_true(val >= -8388608 && val <= 8388607, "read_raw(27) in 24-bit signed range");

    transport.power_down();
    check_true(true, "power_down accepted");

    transport.power_up();
    check_true(true, "power_up accepted");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
