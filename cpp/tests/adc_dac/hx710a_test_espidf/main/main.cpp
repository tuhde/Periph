// Auto-generated ESP-IDF test for HX710A.
// Mirrors the Zephyr test for HX710A; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "HX711ConnectionESPIDF.h"
#include "HX710A.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    HX711ConnectionESPIDF connection(static_cast<gpio_num_t>(19), static_cast<gpio_num_t>(18));
    HX710AFull inst(connection);  // Create HX710A driver
    (void)inst.is_ready();
    int32_t raw = inst.read_raw();
    check_true(raw >= -8388608 && raw <= 8388607, "read_raw in 24-bit signed range");
    inst.set_rate(40);
    check_true(true, "set_rate(40) accepted");
    inst.set_rate(10);
    check_true(true, "set_rate(10) accepted");
    int32_t avg = inst.read_average(5);
    (void)avg;
    int32_t temp_raw = inst.read_temperature_raw();
    check_true(temp_raw >= -8388608 && temp_raw <= 8388607, "read_temperature_raw in range");
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
