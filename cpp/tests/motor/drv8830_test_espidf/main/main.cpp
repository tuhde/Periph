// ESP-IDF test for DRV8830.
// Mirrors the Zephyr test for DRV8830; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DRV8830.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = DRV8830Minimal::I2C_ADDRESS,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    DRV8830Minimal motor(connection);                       // Create DRV8830 driver, (connection)
    motor.drive(2.0f);                                      // Drive at regulated voltage, (voltage V, + = forward) → void
    motor.stop();                                           // Coast to standby, () → void

    DRV8830Full full(connection);                           // Create DRV8830 Full driver, (connection)
    full.drive(2.0f);                                       // Drive at regulated voltage, (voltage V, + = forward) → void
    DRV8830Full::Output out = full.readOutput();            // Read back CONTROL, () → Output {float V, Direction}
    check_true(out.direction == DRV8830Full::Direction::Forward, "drive_forward_direction");
    check_true(out.voltage > 1.9f && out.voltage < 2.1f, "drive_forward_voltage");

    full.drive(-1.0f);                                      // Drive at regulated voltage, (voltage V, - = reverse) → void
    check_true(full.readOutput().direction == DRV8830Full::Direction::Reverse, "drive_reverse_direction");

    full.brake();                                           // Short-brake, () → void
    check_true(full.readOutput().direction == DRV8830Full::Direction::Brake, "brake_direction");

    full.stop();                                            // Coast to standby, () → void
    check_true(full.readOutput().direction == DRV8830Full::Direction::Coast, "stop_direction");

    full.clearFault();                                      // Clear fault bits, () → void
    check_true(!full.readFault().fault, "clear_fault");     // Read fault status, () → Fault

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
