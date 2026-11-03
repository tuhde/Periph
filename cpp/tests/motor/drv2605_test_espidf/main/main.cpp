// ESP-IDF test for DRV2605.
// Mirrors the Zephyr test for DRV2605; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DRV2605.h"

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
    dev_cfg.device_address = DRV2605Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    DRV2605Minimal haptic(connection);                     // Create DRV2605 driver, (connection, lra=false)
    check_true(haptic.isReady(), "construct_minimal");
    DRV2605Full full(connection);                          // Create DRV2605 Full driver, (connection, lra=false)
    check_true(full.isReady(), "construct_full");
    check_true(full.readDeviceId() == 3, "device_id");     // Read DEVICE_ID, () → uint8_t
    full.playEffect(DRV2605Minimal::EFFECT_STRONG_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
    check_true(full.waitDone(3000), "effect_finishes");    // Wait for playback, (timeoutMs) → bool
    DRV2605Full::SequenceStep seq[3] = {DRV2605Full::SequenceStep::effect(1), DRV2605Full::SequenceStep::wait(100), DRV2605Full::SequenceStep::effect(1)};
    full.playSequence(seq, 3);                             // Play a sequence, (items, count) → bool
    check_true(full.waitDone(3000), "sequence_finishes");
    full.rtpLevel(0.5f);                                   // Stream an RTP amplitude, (level 0.0–1.0) → bool
    float vdd = full.readSupplyVoltage();                  // Read supply voltage, () → float V
    check_true(vdd > 2.0f && vdd < 6.0f, "supply_voltage_plausible");
    full.stop();                                           // Cancel playback, () → void
    check_true(!full.isPlaying(), "stop_clears_go");
    full.diagnose();                                       // Run actuator diagnostics, () → bool
    full.calibrate(false, 3.0f, 3.0f);                      // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → Calibration
    check_true(!full.isPlaying(), "calibrate_completes");
    DRV2605Full::Status st = full.readStatus();            // Read status flags, () → Status
    check_true(!st.overTemp, "no_overtemp");
    check_true(!st.overCurrent, "no_overcurrent");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
