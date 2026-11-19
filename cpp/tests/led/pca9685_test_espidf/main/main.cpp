// Auto-generated ESP-IDF test for Pca9685.
// Mirrors the Zephyr test for Pca9685; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Pca9685.h"

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
    dev_cfg.device_address = 0x40;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Pca9685Minimal pwm(connection);
    Pca9685Full full(connection);

    check_true(Pca9685Minimal::CHANNELS == 16, "constants");

    check_true(pwm.set_duty(0, 0.5f) && pwm.set_duty(15, 0.0f) && pwm.set_duty(1, 1.0f), "set_duty");
    check_true(!pwm.set_duty(16, 0.5f) && !pwm.set_duty(0, 1.5f), "set_duty_range");

    uint16_t on = 0, off = 0;
    check_true(full.set_pwm(2, 409, 1228) && !full.set_pwm(2, 5, 5), "set_pwm");
    check_true(full.get_pwm(2, on, off) && on == 409 && off == 1228, "get_pwm");
    check_true(full.set_full_on(3) && full.set_full_off(3), "full_on_off");
    check_true(full.set_all_duty(0.1f) && full.set_all_pwm(0, 2048) && full.set_all_duty(0.0f), "all");
    check_true(full.set_frequency(50.0f), "set_frequency");
    float hz = full.get_frequency();
    check_true(hz > 49.9f && hz < 50.1f, "get_frequency");
    check_true(full.set_pulse_width(15, 0.0015f), "pulse_width");
    full.sleep();
    full.wake();
    full.restart();
    check_true(true, "sleep_wake");
    check_true(full.set_output_mode(false, false, Pca9685Full::OFF_LOW), "output_mode");
    full.set_update_on_ack(false);
    check_true(full.set_allcall(false, 0x70), "allcall");
    check_true(full.set_subaddress(1, 0x71, false), "subaddress");
    uint8_t mode1 = 0xFF, mode2 = 0;
    full.read_mode(mode1, mode2);
    check_true((mode1 & 0x10) == 0 && (mode2 & 0x04) == 0x04, "read_mode");
    full.reset();
    check_true(true, "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
