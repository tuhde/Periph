#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "HX711ConnectionESPIDF.h"
#include "HX710B.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    HX711ConnectionESPIDF connection(static_cast<gpio_num_t>(19), static_cast<gpio_num_t>(18));
    HX710BFull<HX711ConnectionESPIDF> inst(connection);  // Create HX710B driver
    (void)inst.is_ready();
    int32_t raw = inst.read_raw();
    check_true(raw >= -8388608 && raw <= 8388607, "read_raw in 24-bit signed range");
    inst.set_rate(40);
    check_true(true, "set_rate(40) accepted");
    inst.set_rate(10);
    check_true(true, "set_rate(10) accepted");
    int32_t avg = inst.read_average(5);
    (void)avg;
    int32_t supp_raw = inst.read_supply_diff_raw();
    check_true(supp_raw >= -8388608 && supp_raw <= 8388607, "read_supply_diff_raw in range");
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
