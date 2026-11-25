#include <Wire.h>
#include <Periph.h>

#include <stdarg.h>
#include <stdio.h>

static void print_fmt(const char *fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

void setup() {
    Wire.begin();
    Serial.begin(115200);
    I2CConnection connection(Wire, DRV2605Minimal::I2C_ADDRESS);
    const bool LRA = false;                                // set to true for a linear resonant actuator
    DRV2605Full haptic(connection, LRA);                   // Create DRV2605 Full driver, (connection, lra=false)

    // --- Verify and calibrate the actuator ---
    // diagnose() reports a missing or shorted actuator; calibrate() measures the
    // back-EMF so the closed-loop engine matches this particular motor.
    print_fmt("actuator ok: %d\n", haptic.diagnose());        // Run actuator diagnostics, () → bool
    DRV2605Full::Calibration cal = haptic.calibrate(LRA, 3.0f, 3.0f, LRA ? 175.0f : 0.0f);  // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → Calibration
    print_fmt("calibration: ok %d comp %d bemf %d gain %d\n", cal.ok, cal.comp, cal.bemf, cal.bemfGain);

    // --- Play the three click effects ---
    const uint8_t clicks[3] = {DRV2605Minimal::EFFECT_STRONG_CLICK, DRV2605Minimal::EFFECT_DOUBLE_CLICK, DRV2605Minimal::EFFECT_TRIPLE_CLICK};
    for (int i = 0; i < 3; i++) {
        haptic.playEffect(clicks[i]);                      // Play a ROM effect, (effectId 1–123) → bool
        haptic.waitDone();                                 // Wait for playback, (timeoutMs=5000) → bool
        delay(500);
    }

    // --- Heartbeat: two strong clicks 100 ms apart ---
    // The sequencer plays both effects and the delay without host involvement.
    DRV2605Full::SequenceStep beat[3] = {DRV2605Full::SequenceStep::effect(DRV2605Minimal::EFFECT_STRONG_CLICK), DRV2605Full::SequenceStep::wait(100), DRV2605Full::SequenceStep::effect(DRV2605Minimal::EFFECT_STRONG_CLICK)};
    haptic.playSequence(beat, 3);                          // Play a sequence, (items, count 1–8) → bool
    haptic.waitDone();                                     // Wait for playback, (timeoutMs=5000) → bool
    delay(500);

    // --- Two-second amplitude ramp ---
    // Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
    // while the actuator is driven.
    for (int step = 0; step <= 20; step++) {
        haptic.rtpLevel(step / 20.0f);                     // Stream an RTP amplitude, (level 0.0–1.0) → bool
        delay(100);
        print_fmt("vdd %d mV\n", (int)(haptic.readSupplyVoltage() * 1000.0f));  // Read supply voltage, () → float V
        if (LRA) {
            print_fmt("lra %d Hz\n", (int)haptic.readLraFrequency());  // Read LRA frequency, () → float Hz (0 if unavailable)
        }
    }
    haptic.stop();                                         // Cancel playback, () → void

    // --- Report latched faults ---
    DRV2605Full::Status st = haptic.readStatus();          // Read status flags, () → Status
    print_fmt("status diag %d fb %d otemp %d ocp %d\n", st.diagFailed, st.fbTimeout, st.overTemp, st.overCurrent);
}

void loop() {}
