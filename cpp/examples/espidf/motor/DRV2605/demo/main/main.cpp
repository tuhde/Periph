#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DRV2605.h"

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
    const bool LRA = false;                                // set to true for a linear resonant actuator
    DRV2605Full haptic(connection, LRA);                   // Create DRV2605 Full driver, (connection, lra=false)

    // --- Verify and calibrate the actuator ---
    // diagnose() reports a missing or shorted actuator; calibrate() measures the
    // back-EMF so the closed-loop engine matches this particular motor.
    printf("actuator ok: %d\n", haptic.diagnose());        // Run actuator diagnostics, () → bool
    DRV2605Full::Calibration cal = haptic.calibrate(LRA, 3.0f, 3.0f, LRA ? 175.0f : 0.0f);  // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → Calibration
    printf("calibration: ok %d comp %d bemf %d gain %d\n", cal.ok, cal.comp, cal.bemf, cal.bemfGain);

    // --- Play the three click effects ---
    const uint8_t clicks[3] = {DRV2605Minimal::EFFECT_STRONG_CLICK, DRV2605Minimal::EFFECT_DOUBLE_CLICK, DRV2605Minimal::EFFECT_TRIPLE_CLICK};
    for (int i = 0; i < 3; i++) {
        haptic.playEffect(clicks[i]);                      // Play a ROM effect, (effectId 1–123) → bool
        haptic.waitDone();                                 // Wait for playback, (timeoutMs=5000) → bool
        vTaskDelay(pdMS_TO_TICKS(500));
    }

    // --- Heartbeat: two strong clicks 100 ms apart ---
    // The sequencer plays both effects and the delay without host involvement.
    DRV2605Full::SequenceStep beat[3] = {DRV2605Full::SequenceStep::effect(DRV2605Minimal::EFFECT_STRONG_CLICK), DRV2605Full::SequenceStep::wait(100), DRV2605Full::SequenceStep::effect(DRV2605Minimal::EFFECT_STRONG_CLICK)};
    haptic.playSequence(beat, 3);                          // Play a sequence, (items, count 1–8) → bool
    haptic.waitDone();                                     // Wait for playback, (timeoutMs=5000) → bool
    vTaskDelay(pdMS_TO_TICKS(500));

    // --- Two-second amplitude ramp ---
    // Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
    // while the actuator is driven.
    for (int step = 0; step <= 20; step++) {
        haptic.rtpLevel(step / 20.0f);                     // Stream an RTP amplitude, (level 0.0–1.0) → bool
        vTaskDelay(pdMS_TO_TICKS(100));
        printf("vdd %d mV\n", (int)(haptic.readSupplyVoltage() * 1000.0f));  // Read supply voltage, () → float V
        if (LRA) {
            printf("lra %d Hz\n", (int)haptic.readLraFrequency());  // Read LRA frequency, () → float Hz (0 if unavailable)
        }
    }
    haptic.stop();                                         // Cancel playback, () → void

    // --- Report latched faults ---
    DRV2605Full::Status st = haptic.readStatus();          // Read status flags, () → Status
    printf("status diag %d fb %d otemp %d ocp %d\n", st.diagFailed, st.fbTimeout, st.overTemp, st.overCurrent);
}
