// Auto-generated ESP-IDF test for Max17048.
// Mirrors the Zephyr test for Max17048; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include <math.h>
#include "Max17048.h"

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
    dev_cfg.device_address = 0x36;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Max17048Full gauge(connection);  // Create Max17048 driver
    check_true(gauge.voltage() >= 2.5f && gauge.voltage() <= 4.6f, "voltage range");
    check_true(gauge.percent() >= 0.0f && gauge.percent() <= 100.0f, "percent range");
    check_true((gauge.version() & 0xFFF0) == 0x0010, "version");
    check_true(fabsf((gauge.raw_voltage() >> 4) * 1.25e-3f - gauge.voltage()) < 0.05f, "raw_voltage matches voltage");
    check_true(fabsf(gauge.charge_rate()) < 1000.0f, "charge_rate readable");
    check_true((gauge.set_rcomp(0x97), gauge.get_rcomp() == 0x97), "rcomp");
    check_true((gauge.compensate_temperature(20.0f), gauge.get_rcomp() == 0x97), "compensate 20 C");
    check_true((gauge.set_alert_soc(10), gauge.get_alert_soc() == 10), "alert_soc");
    gauge.set_alert_soc(4);
    float lo = 0, hi = 0;
    gauge.set_voltage_alert(3.0f, 4.2f);
    gauge.get_voltage_alert(&lo, &hi);
    check_true(fabsf(lo - 3.0f) < 0.001f && fabsf(hi - 4.2f) < 0.001f, "voltage_alert");
    gauge.set_voltage_alert(0.0f, 5.1f);
    check_true((gauge.set_reset_voltage(2.8f), fabsf(gauge.get_reset_voltage() - 2.8f) < 0.001f), "reset_voltage");
    gauge.set_reset_voltage(3.0f);
    float hib = 0, act = 0;
    gauge.set_hibernate_thresholds(26.624f, 0.06f);
    gauge.get_hibernate_thresholds(&hib, &act);
    check_true(fabsf(hib - 26.624f) < 0.001f && fabsf(act - 0.06f) < 0.001f, "hibernate_thresholds");
    gauge.clear_status(0x3F00);
    check_true((gauge.read_status() & 0x3F00) == 0, "status flags cleared");
    check_true(gauge.pollInterrupt() == 0, "pollInterrupt");
    check_true((gauge.write_config(gauge.read_config()), (gauge.read_config() >> 8) == 0x97), "config");
    gauge.enable_sleep(true);
    gauge.sleep();
    vTaskDelay(pdMS_TO_TICKS(5));
    gauge.wake();
    gauge.enable_sleep(false);
    check_true(gauge.voltage() > 0.0f, "wake: voltage readable");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
