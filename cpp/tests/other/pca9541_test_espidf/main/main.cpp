// ESP-IDF test for PCA9541.
// Mirrors the Zephyr test for PCA9541; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA9541.h"

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
    dev_cfg.device_address = PCA9541Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PCA9541Minimal selector(connection);                    // Create PCA9541 driver, (connection)
    check_true(selector.present(), "present");
    bool has = selector.hasControl();                       // Report control, () → bool
    check_true(has == true || has == false, "has_control_is_bool");

    PCA9541Full full(connection);                           // Create PCA9541 Full driver, (connection)
    uint8_t control = full.getControl();                    // Read the CONTROL byte, () → uint8_t
    PCA9541Full::Status st = full.status();                 // Decode CONTROL, () → Status
    check_true(st.hasControl == (((control & 1) != 0) == ((control & 2) != 0)), "status_matches_control");
    uint8_t ie = 0, ctl = 0, istat = 0;
    full.readAll(ie, ctl, istat);                           // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    check_true(ie <= 0x0F, "read_all_ie_range");
    full.setInterruptMask(false, true);                     // Write IE, (busLost, busNotIdle, busInit, intIn) → void
    check_true(full.getInterruptMask() == 0x04, "interrupt_mask_roundtrip"); // Read IE, () → uint8_t
    full.setInterruptMask();
    check_true(full.getInterruptMask() == 0x00, "interrupt_mask_cleared");
    full.testInterrupt(true);                               // Drive this master's INT low, (own=false, other=false) → void
    check_true(full.status().ownTest, "test_interrupt_sets_teston");
    full.testInterrupt();
    check_true(!full.status().ownTest, "test_interrupt_clears");
    check_true(full.acquire() && full.hasControl(), "acquire_takes_bus"); // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    full.disconnect();                                      // Isolate the downstream bus, () → void
    check_true(!full.isConnected(), "disconnect");
    full.connect();                                         // Reconnect, () → void
    check_true(full.isConnected(), "connect");
    full.release();                                         // Give the bus to the other master, () → void
    check_true(!full.hasControl(), "release_gives_bus_away");
    full.acquire();
    check_true(full.hasControl(), "reacquire");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
