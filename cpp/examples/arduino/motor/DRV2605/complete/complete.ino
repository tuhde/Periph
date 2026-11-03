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
    DRV2605Full haptic(connection);                        // Create DRV2605 Full driver, (connection, lra=false)
    print_fmt("device id %d\n", haptic.readDeviceId());       // Read DEVICE_ID, () → uint8_t
                                                          // bits 7:5 of STATUS; 3 for the DRV2605
    haptic.playEffect(DRV2605Minimal::EFFECT_STRONG_CLICK);  // Play a ROM effect, (effectId 1–123) → bool
                                                          // writes slot 1 and the end marker, then sets GO
    haptic.waitDone();                                     // Wait for playback, (timeoutMs=5000) → bool
                                                          // polls GO until the sequence finishes
    DRV2605Full::SequenceStep seq[3] = {DRV2605Full::SequenceStep::effect(1), DRV2605Full::SequenceStep::wait(100), DRV2605Full::SequenceStep::effect(47)};
    haptic.playSequence(seq, 3);                           // Play a sequence, (items, count 1–8) → bool
                                                          // an effect step plays a ROM effect, a wait step delays in 10 ms steps
    haptic.waitDone();                                     // Wait for playback, (timeoutMs=5000) → bool
                                                          // polls GO until the sequence finishes
    print_fmt("playing %d\n", haptic.isPlaying());            // Check playback, () → bool
                                                          // reads the GO bit
    haptic.stop();                                         // Cancel playback, () → void
                                                          // clears GO; also leaves RTP mode

    haptic.setLibrary(1);                                  // Select ROM library, (library 0–6) → bool
                                                          // 0 empty, 1–5 ERM libraries A–E, 6 LRA library
    haptic.setActuator(false);                             // Select actuator type, (lra) → void
                                                          // sets N_ERM_LRA; recalibrate after changing it
    haptic.setOpenLoop(true);                              // Select open-loop drive, (enabled) → void
                                                          // ERM_OPEN_LOOP for an ERM, LRA_OPEN_LOOP for an LRA
    haptic.setBidirectional(true);                         // Select bidirectional input, (enabled) → bool
                                                          // unidirectional is only valid closed-loop
    haptic.setRatedVoltage(3.0f);                          // Set rated voltage, (volts V, lraFreqHz Hz) → bool
                                                          // code = V / 21.33 mV for an ERM
    haptic.setOverdriveClamp(3.0f);                        // Set overdrive clamp, (volts V, lraFreqHz Hz) → bool
                                                          // code = V / 21.96 mV in open loop
    haptic.setRatedVoltageCode(0x3F);                      // Set rated voltage code, (code 0–255) → void
                                                          // raw RATED_VOLTAGE register value
    haptic.setOverdriveClampCode(0x89);                    // Set overdrive clamp code, (code 0–255) → void
                                                          // raw OD_CLAMP register value
    haptic.setDriveTime(2.0f);                             // Set drive time, (ms) → void
                                                          // ERM back-EMF sample period (1 ms + 0.2 ms × code)
    haptic.setFeedback(3, 1, 2);                           // Set feedback control, (brakeFactor 0–7, loopGain 0–3, bemfGain 0–3) → bool
                                                          // keeps the actuator-type bit
    haptic.setAdvanced();                                  // Set CONTROL1–3 fields, (startupBoost, brakeStabilizer, sampleTime, blankingTime, idissTime, lraDriveTwice, supplyComp, noiseGate) → bool
                                                          // defaults match the chip's reset values
    haptic.setTimeOffsets(0, 0, 0, 0);                     // Set library time offsets, (overdriveMs, sustainPosMs, sustainNegMs, brakeMs) → bool
                                                          // ODT/SPT/SNT/BRT in 5 ms steps, open-loop ROM playback only

    haptic.rtpLevel(0.5f);                                 // Stream an RTP amplitude, (level 0.0–1.0) → bool
                                                          // enters RTP mode with signed data; 0.5 → 64
    delay(500);
    haptic.setRtpFormat(false);                            // Select RTP data format, (isUnsigned) → void
                                                          // false = signed (default)
    haptic.rtpWrite(0x3F);                                 // Write a raw RTP byte, (value 0–255) → void
                                                          // 0x3F = half scale in signed bidirectional mode
    delay(500);
    haptic.stop();                                         // Cancel playback, () → void
                                                          // zeroes RTP_INPUT and returns to internal trigger
    haptic.setMode(DRV2605Minimal::MODE_INTERNAL_TRIGGER);  // Select operating mode, (mode 0–7) → bool
                                                          // 0 internal trigger … 7 auto-calibration
    haptic.setInput(false);                                // Select PWM/analog input, (analog) → void
                                                          // N_PWM_ANALOG; used with mode 3
    haptic.setHiZ(false);                                  // Set high-impedance outputs, (enabled) → void
                                                          // true floats OUT+/OUT−

    DRV2605Full::Calibration cal = haptic.calibrate(false, 3.0f, 3.0f);  // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → Calibration
                                                          // takes up to 1.2 s
    print_fmt("calibrated %d comp %d bemf %d gain %d\n", cal.ok, cal.comp, cal.bemf, cal.bemfGain);
    cal = haptic.getCalibration();                         // Read calibration results, () → Calibration
                                                          // A_CAL_COMP, A_CAL_BEMF, BEMF_GAIN
    haptic.setCalibration(cal.comp, cal.bemf, cal.bemfGain);  // Restore calibration, (comp, bemf, bemfGain) → bool
                                                          // avoids recalibrating after a reset
    print_fmt("actuator ok %d\n", haptic.diagnose());         // Run actuator diagnostics, () → bool
                                                          // true if DIAG_RESULT = 0
    haptic.setAudioToVibe(true, 20, 125, 0.1f, 1.8f, 0.1f, 1.0f);  // Configure audio-to-vibe, (acCouple, peakTimeMs, filterHz, minInputV, maxInputV, minDrive, maxDrive) → bool
                                                          // enters audio-to-vibe mode
    haptic.setMode(DRV2605Minimal::MODE_INTERNAL_TRIGGER);  // Select operating mode, (mode 0–7) → bool
                                                          // back to internal trigger

    haptic.playEffect(DRV2605Minimal::EFFECT_BUZZ);        // Play a ROM effect, (effectId 1–123) → bool
                                                          // a buzz, so the supply and LRA readings are valid
    delay(200);
    print_fmt("vdd %d mV\n", (int)(haptic.readSupplyVoltage() * 1000.0f));  // Read supply voltage, () → float V
                                                          // VBAT × 5.6 / 255; valid during playback
    print_fmt("lra %d Hz\n", (int)haptic.readLraFrequency());  // Read LRA frequency, () → float Hz (0 if unavailable)
                                                          // 0 when LRA_PERIOD is 0
    haptic.stop();                                         // Cancel playback, () → void
    DRV2605Full::Status st = haptic.readStatus();          // Read status flags, () → Status
                                                          // diagFailed, fbTimeout, overTemp, overCurrent; clears the latches
    print_fmt("status diag %d fb %d otemp %d ocp %d\n", st.diagFailed, st.fbTimeout, st.overTemp, st.overCurrent);
    print_fmt("otp %d\n", haptic.otpProgrammed());            // Read OTP status, () → bool
                                                          // true once the OTP has been burned
    // haptic.programOtp(true);                            // IRREVERSIBLE one-time OTP burn, (confirm) → bool
    haptic.standby();                                      // Enter software standby, () → void
                                                          // also stops playback
    haptic.wake();                                         // Leave software standby, () → void
    haptic.reset();                                        // Reset all registers, () → bool
                                                          // re-create the driver afterwards
}

void loop() {}
