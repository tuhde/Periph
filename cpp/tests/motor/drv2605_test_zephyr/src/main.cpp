#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DRV2605.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printk("FAIL device_ready\n");
        return 1;
    }
    I2CConnectionZephyr connection(i2c_dev, DRV2605Minimal::I2C_ADDRESS);
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
    DRV2605Full::Status st = full.readStatus();            // Read status flags, () → Status
    check_true(!st.overTemp, "no_overtemp");
    check_true(!st.overCurrent, "no_overcurrent");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
