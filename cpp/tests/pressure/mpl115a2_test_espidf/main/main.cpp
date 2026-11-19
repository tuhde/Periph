// Auto-generated ESP-IDF test for MPL115A2.
// Mirrors the Zephyr test for MPL115A2; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MPL115A2.h"

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
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x60;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MPL115A2Full baro(connection);
    float p = baro.read_pressure();
    check_true(p >= 50000.0f && p <= 115000.0f, "pressure_range");
    float t = baro.read_temperature();
    check_true(t >= -40.0f && t <= 105.0f, "temperature_range");
    uint16_t padc, tadc;
    baro.read_raw(padc, tadc);
    check_true(padc <= 1023 && tadc <= 1023, "raw_range");
    float c = baro.compensate(padc, tadc);
    check_true(c >= 50000.0f && c <= 115000.0f, "compensate_range");
    float pa, tc;
    baro.read(pa, tc);
    check_true(pa >= 50000.0f && pa <= 115000.0f && tc >= -40.0f && tc <= 105.0f, "read_both");
    float hpa = baro.pressure_hpa();
    check_true(hpa >= 500.0f && hpa <= 1150.0f, "pressure_hpa");
    float alt = baro.altitude();
    check_true(alt >= -1000.0f && alt <= 6000.0f, "altitude");
    baro.reload_coefficients();
    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);
    check_true(a0 != 0.0f, "coefficients");
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}

