// ESP-IDF test for MCP3422.
// Mirrors the Zephyr test for MCP3422; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP3422.h"

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
    dev_cfg.device_address = MCP3422Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MCP3422Minimal adc(connection);                         // Create MCP3422 driver, (connection)
    int32_t raw = adc.readRaw(0);                           // Read a signed code, (channel 0–1) → int32_t
    check_true(adc.lastReadOk() && raw >= -32768 && raw <= 32767, "raw_in_16_bit_range");
    float v = adc.readVoltage(1);                           // Read the differential voltage, (channel 0–1) → float V
    check_true(adc.lastReadOk() && v >= -2.048f && v <= 2.048f, "voltage_in_range");
    float both[2];
    check_true(adc.readAll(both), "read_all_two_values");   // Read both channels, (float out[2]) → bool

    MCP3422Full full(connection);                           // Create MCP3422 Full driver, (connection)
    check_true(full.setResolution(12) && full.setGain(2), "selection_accepted"); // Select resolution / gain, (bits|gain) → bool
    check_true(full.getResolution() == 12 && full.getGain() == 2, "selection_roundtrip");
    check_true(fabsf(full.fullScale() - 1.024f) < 1e-6f, "full_scale"); // Full-scale range, () → float V
    int32_t r12 = full.readRaw(0);
    check_true(full.lastReadOk() && r12 >= -2048 && r12 <= 2047, "one_shot_12_bit_range");
    full.setGain(1);
    full.startContinuous(0);                                // Start continuous conversion, (channel=-1) → void
    check_true(full.isContinuous(), "continuous_flag");     // Report the mode, () → bool
    int32_t rc = full.readContinuousRaw();                  // Next fresh code, () → int32_t
    check_true(full.lastReadOk() && rc >= -2048 && rc <= 2047, "continuous_sample_in_range");
    check_true((full.readConfig() & 0x10) != 0, "read_config_continuous_bit"); // Read the config byte, () → uint8_t
    full.stopContinuous();                                  // Back to one-shot / standby, () → void
    check_true(!full.isContinuous(), "stopped");
    const uint8_t ch[2] = {0, 1};
    float scanned[2];
    check_true(full.readScan(ch, 2, scanned), "read_scan_two_values"); // Scan channels, (channels, count, float out[]) → bool

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
