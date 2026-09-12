#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711ConnectionZephyr.h"
#include "HX710A.h"

#define HX710A_DOUT_NODE DT_ALIAS(hx710a_dout)
#define HX710A_SCK_NODE  DT_ALIAS(hx710a_sck)

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX710A_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX710A_SCK_NODE,  gpios);

    HX711ConnectionZephyr connection(dout, pd_sck);
    HX710AFull<HX711ConnectionZephyr> chip(connection);

    check_true(true, "is_ready compiles");

    int32_t raw = chip.read_raw();
    check_true(raw >= -8388608 && raw <= 8388607, "read_raw in 24-bit signed range");

    chip.set_rate(40);
    check_true(true, "set_rate(40) accepted");

    chip.set_rate(10);
    check_true(true, "set_rate(10) accepted");

    int32_t avg = chip.read_average(3);
    check_true(avg >= -8388608 && avg <= 8388607, "read_average in range");

    chip.tare(3);
    check_true(true, "tare accepted");

    chip.set_scale(420.0f);
    check_true(true, "set_scale accepted");

    float weight = chip.read_weight(1);
    check_true(true, "read_weight returns float");

    int32_t temp_raw = chip.read_temperature_raw();
    check_true(temp_raw >= -8388608 && temp_raw <= 8388607, "read_temperature_raw in range");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
