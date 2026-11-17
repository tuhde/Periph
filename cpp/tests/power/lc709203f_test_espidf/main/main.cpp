// Auto-generated ESP-IDF test for LC709203F.
// Mirrors the Zephyr test for LC709203F; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include <math.h>
#include "LC709203F.h"

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
    dev_cfg.device_address = 0x0B;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LC709203FFull gauge(connection);  // Create LC709203F driver
    check_true(gauge.voltage() >= 2.5f && gauge.voltage() <= 4.6f, "voltage range");
    check_true(gauge.valid(), "crc valid");
    check_true(gauge.percent() >= 0.0f && gauge.percent() <= 100.0f, "percent range");
    check_true(gauge.temperature() > 0.0f && gauge.temperature() < 60.0f, "temperature range");
    check_true(gauge.rsoc() <= 100, "rsoc range");
    check_true(gauge.profile_id() != 0, "profile_id");
    check_true(gauge.get_profile() == 1, "profile");
    check_true((gauge.set_temperature(30.0f), fabsf(gauge.temperature() - 30.0f) < 0.2f), "set_temperature");
    check_true((gauge.set_apa(0x19), gauge.get_apa() == 0x19), "apa");
    check_true((gauge.set_apt(0x1E), gauge.get_apt() == 0x1E), "apt");
    check_true((gauge.set_alarm_rsoc(20), gauge.get_alarm_rsoc() == 20), "alarm_rsoc");
    check_true((gauge.set_alarm_voltage(3.5f), fabsf(gauge.get_alarm_voltage() - 3.5f) < 0.002f), "alarm_voltage");
    check_true(gauge.pollInterrupt() <= 3, "pollInterrupt");
    check_true((gauge.init_rsoc(), gauge.rsoc() <= 100), "init_rsoc");
    gauge.sleep();
    vTaskDelay(pdMS_TO_TICKS(5));
    gauge.wake();
    check_true(!gauge.is_sleeping(), "wake: not sleeping");
    check_true(gauge.voltage() > 0.0f, "wake: voltage readable");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
