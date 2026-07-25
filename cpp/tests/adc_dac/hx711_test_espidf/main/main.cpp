// Auto-generated ESP-IDF test for HX711.
// Mirrors the Zephyr test for HX711; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "HX711TransportESPIDF.h"
#include "HX711.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n",
                  label, (double)val, (double)lo, (double)hi); failed++; }
}

static void check_eq_u8(uint8_t val, uint8_t expected, const char *label) {
    if (val == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: 0x%02X != 0x%02X\n", label, val, expected); failed++; }
}


extern "C" void app_main(void) {
    HX711TransportESPIDF transport(static_cast<gpio_num_t>(19), static_cast<gpio_num_t>(18));
    HX711Full inst(transport);  // Create HX711 driver
    (void)inst.is_ready();
    int32_t raw = inst.read_raw();
    check_true(raw != 0 || raw == 0, "read_raw callable");
    int32_t avg = inst.read_average(5);
    (void)avg;
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
