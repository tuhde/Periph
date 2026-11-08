#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AXP2101.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
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
    dev_cfg.device_address = 0x34;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AXP2101Minimal chip(connection);                       // Create AXP2101 driver, (connection)
    AXP2101Full full(connection);                          // Create AXP2101 Full driver, (connection)

    float v = chip.batteryVoltage();
    check_true(v >= 0.0f && v <= 5.0f, "battery_voltage_plausible");
    check_true(chip.batteryPercent() <= 100, "battery_percent_range");
    check_true(chip.chargeState() <= 5, "charge_state_range");
    check_true(chip.valid(), "bus_check_ok");
    check_true(full.valid(), "construct_full");
    check_true(full.vbusVoltage() >= 0.0f && full.vbusVoltage() <= 15.0f, "vbus_voltage_range");
    check_true(full.systemVoltage() >= 0.0f && full.systemVoltage() <= 6.0f, "system_voltage_range");
    check_true(full.tsVoltage() >= 0.0f && full.tsVoltage() <= 4.0f, "ts_voltage_range");
    float t = full.dieTemperature();
    check_true(t >= -40.0f && t <= 150.0f, "die_temperature_range");
    AXP2101Readings all;
    full.readAll(all);
    check_true(all.batteryPercent <= 100, "read_all");
    AXP2101Status st;
    full.status(st);
    check_true(st.batteryDirection != nullptr, "status_direction");
    bool en = false;
    float volts = 0.0f;
    check_true(full.getRail("dcdc1", en, volts) && volts >= 1.4f && volts <= 3.5f, "get_rail_dcdc1");
    check_true(full.setInputVoltageLimit(4.36f), "set_input_voltage_limit");
    uint8_t b = 0;
    check_true(full.writeDataBuffer(0, 0x5A) && full.readDataBuffer(0, b) && b == 0x5A, "data_buffer_roundtrip");
    check_true(full.enableInterrupt(AXP2101Full::SOURCE_VBUS_INSERT), "enable_interrupt");
    full.pollInterrupt();
    check_true(full.valid(), "poll_interrupt");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
