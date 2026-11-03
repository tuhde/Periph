#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DRV2605.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    I2CConnectionPicoSDK connection(i2c0, DRV2605Minimal::I2C_ADDRESS);
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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
