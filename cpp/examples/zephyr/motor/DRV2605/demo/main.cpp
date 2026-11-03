#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DRV2605.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DRV2605Minimal::I2C_ADDRESS);
    const bool LRA = false;                                // set to true for a linear resonant actuator
    DRV2605Full haptic(connection, LRA);                   // Create DRV2605 Full driver, (connection, lra=false)

    // --- Verify and calibrate the actuator ---
    // diagnose() reports a missing or shorted actuator; calibrate() measures the
    // back-EMF so the closed-loop engine matches this particular motor.
    printk("actuator ok: %d\n", haptic.diagnose());        // Run actuator diagnostics, () → bool
    DRV2605Full::Calibration cal = haptic.calibrate(LRA, 3.0f, 3.0f, LRA ? 175.0f : 0.0f);  // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → Calibration
    printk("calibration: ok %d comp %d bemf %d gain %d\n", cal.ok, cal.comp, cal.bemf, cal.bemfGain);

    // --- Play the three click effects ---
    const uint8_t clicks[3] = {DRV2605Minimal::EFFECT_STRONG_CLICK, DRV2605Minimal::EFFECT_DOUBLE_CLICK, DRV2605Minimal::EFFECT_TRIPLE_CLICK};
    for (int i = 0; i < 3; i++) {
        haptic.playEffect(clicks[i]);                      // Play a ROM effect, (effectId 1–123) → bool
        haptic.waitDone();                                 // Wait for playback, (timeoutMs=5000) → bool
        k_sleep(K_MSEC(500));
    }

    // --- Heartbeat: two strong clicks 100 ms apart ---
    // The sequencer plays both effects and the delay without host involvement.
    DRV2605Full::SequenceStep beat[3] = {DRV2605Full::SequenceStep::effect(DRV2605Minimal::EFFECT_STRONG_CLICK), DRV2605Full::SequenceStep::wait(100), DRV2605Full::SequenceStep::effect(DRV2605Minimal::EFFECT_STRONG_CLICK)};
    haptic.playSequence(beat, 3);                          // Play a sequence, (items, count 1–8) → bool
    haptic.waitDone();                                     // Wait for playback, (timeoutMs=5000) → bool
    k_sleep(K_MSEC(500));

    // --- Two-second amplitude ramp ---
    // Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
    // while the actuator is driven.
    for (int step = 0; step <= 20; step++) {
        haptic.rtpLevel(step / 20.0f);                     // Stream an RTP amplitude, (level 0.0–1.0) → bool
        k_sleep(K_MSEC(100));
        printk("vdd %d mV\n", (int)(haptic.readSupplyVoltage() * 1000.0f));  // Read supply voltage, () → float V
        if (LRA) {
            printk("lra %d Hz\n", (int)haptic.readLraFrequency());  // Read LRA frequency, () → float Hz (0 if unavailable)
        }
    }
    haptic.stop();                                         // Cancel playback, () → void

    // --- Report latched faults ---
    DRV2605Full::Status st = haptic.readStatus();          // Read status flags, () → Status
    printk("status diag %d fb %d otemp %d ocp %d\n", st.diagFailed, st.fbTimeout, st.overTemp, st.overCurrent);
    return 0;
}
