#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, DRV2605Minimal::I2C_ADDRESS);

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

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
