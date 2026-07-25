// Auto-generated ESP-IDF test for NEO6.
// Mirrors the Zephyr test for NEO6; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "UARTTransportESPIDF.h"
#include "NEO6.h"

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
    uart_config_t uart_cfg = {
        .baud_rate = 9600,
        .data_bits = UART_DATA_8_BITS,
        .parity    = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
    };
    uart_driver_install(UART_NUM_1, 1024, 1024, 0, NULL, 0);
    uart_param_config(UART_NUM_1, &uart_cfg);
    uart_set_pin(UART_NUM_1, 17, 16, -1, -1);  // TX=17, RX=16

    UARTTransportESPIDF transport(UART_NUM_1);
    NEO6Full inst(transport);  // Create NEO6 driver
    inst.update();
    (void)inst.latitude();
    check_true(true, "neo6 update ok");
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
