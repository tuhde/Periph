'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { DRV2605Full } = require('periph/src/chips/motor/drv2605');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DRV2605Full.I2C_ADDRESS);
    const haptic = new DRV2605Full(connection, false);                             // Create DRV2605 Full driver, (connection, lra=false)
    await haptic.init();                                                           // Reset, verify and configure the chip, () → None
                                                                                       // DEV_RESET, DEVICE_ID check, leave standby, select ERM/LRA

    console.log('device id', await haptic.readDeviceId());                         // Read DEVICE_ID, () → number
                                                                                       // bits 7:5 of STATUS; 3 for the DRV2605
    await haptic.playEffect(DRV2605Full.EFFECT_STRONG_CLICK);                      // Play a ROM effect, (effectId 1–123) → None
                                                                                       // writes slot 1 and the end marker, then sets GO
    await haptic.waitDone();                                                       // Wait for playback, (timeoutMs=5000) → boolean
                                                                                       // polls GO until the sequence finishes
    await haptic.playSequence([DRV2605Full.EFFECT_STRONG_CLICK, ['wait', 100], DRV2605Full.EFFECT_BUZZ]); // Play a sequence, (items ≤ 8) → None
                                                                                       // number = effect, ['wait', ms] = delay in 10 ms steps
    await haptic.waitDone();                                                       // Wait for playback, (timeoutMs=5000) → boolean
    console.log('playing', await haptic.isPlaying());                              // Check playback, () → boolean
                                                                                       // reads the GO bit
    await haptic.stop();                                                           // Cancel playback, () → None
                                                                                       // clears GO; also leaves RTP mode

    await haptic.setLibrary(1);                                                    // Select ROM library, (library 0–6) → None
                                                                                       // 0 empty, 1–5 ERM libraries A–E, 6 LRA library
    await haptic.setActuator(false);                                               // Select actuator type, (lra) → None
                                                                                       // sets N_ERM_LRA; recalibrate after changing it
    await haptic.setOpenLoop(true);                                                // Select open-loop drive, (enabled) → None
                                                                                       // ERM_OPEN_LOOP for an ERM, LRA_OPEN_LOOP for an LRA
    await haptic.setBidirectional(true);                                           // Select bidirectional input, (enabled) → None
                                                                                       // unidirectional is only valid closed-loop
    await haptic.setRatedVoltage({ volts: 3.0 });                                  // Set rated voltage, ({volts V | code 0–255, lraFreqHz Hz}) → None
                                                                                       // code = V / 21.33 mV for an ERM
    await haptic.setOverdriveClamp({ volts: 3.0 });                                // Set overdrive clamp, ({volts V | code 0–255, lraFreqHz Hz}) → None
                                                                                       // code = V / 21.96 mV in open loop
    await haptic.setDriveTime(2.0);                                                // Set drive time, (ms) → None
                                                                                       // ERM back-EMF sample period (1 ms + 0.2 ms × code)
    await haptic.setFeedback(3, 1, 2);                                             // Set feedback control, (brakeFactor 0–7, loopGain 0–3, bemfGain 0–3) → None
                                                                                       // keeps the actuator-type bit
    await haptic.setAdvanced();                                                    // Set CONTROL1–3 fields, ({startupBoost, brakeStabilizer, sampleTime, blankingTime, idissTime, lraDriveTwice, supplyComp, noiseGate}) → None
                                                                                       // defaults match the chip's reset values
    await haptic.setTimeOffsets({ overdriveMs: 0, sustainPosMs: 0, sustainNegMs: 0, brakeMs: 0 }); // Set library time offsets, ({overdriveMs, sustainPosMs, sustainNegMs, brakeMs}) → None
                                                                                       // ODT/SPT/SNT/BRT for open-loop ROM playback

    await haptic.rtpLevel(0.5);                                                    // Stream an RTP amplitude, (level 0.0–1.0) → None
                                                                                       // enters RTP mode with signed data; 0.5 → 64
    await sleep(500);
    await haptic.setRtpFormat(false);                                              // Select RTP data format, (unsigned) → None
                                                                                       // false = signed (default)
    await haptic.rtpWrite(0x3F);                                                   // Write a raw RTP byte, (value 0–255) → None
                                                                                       // 0x3F = half scale in signed bidirectional mode
    await sleep(500);
    await haptic.stop();                                                           // Cancel playback, () → None
                                                                                       // zeroes RTP_INPUT and returns to internal trigger

    await haptic.setMode(DRV2605Full.MODE_INTERNAL_TRIGGER);                       // Select operating mode, (mode 0–7) → None
                                                                                       // 0 internal trigger … 7 auto-calibration
    await haptic.setInput(false);                                                  // Select PWM/analog input, (analog) → None
                                                                                       // N_PWM_ANALOG; used with mode 3
    await haptic.setHiZ(false);                                                    // Set high-impedance outputs, (enabled) → None
                                                                                       // true floats OUT+/OUT−
    const cal = await haptic.calibrate(false, 3.0, 3.0);                           // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → {ok, comp, bemf, bemfGain}
                                                                                       // takes up to 1.2 s
    console.log('calibrated', cal);
    const stored = await haptic.getCalibration();                                  // Read calibration results, () → {comp, bemf, bemfGain}
                                                                                       // A_CAL_COMP, A_CAL_BEMF, BEMF_GAIN
    await haptic.setCalibration(stored.comp, stored.bemf, stored.bemfGain);        // Restore calibration, (comp, bemf, bemfGain) → None
                                                                                       // avoids recalibrating after a reset
    console.log('actuator ok', await haptic.diagnose());                           // Run actuator diagnostics, () → boolean
                                                                                       // true if DIAG_RESULT = 0
    await haptic.setAudioToVibe({ acCouple: true, peakTimeMs: 20, filterHz: 125, minInputV: 0.1, maxInputV: 1.8, minDrive: 0.1, maxDrive: 1.0 }); // Configure audio-to-vibe, ({acCouple, peakTimeMs, filterHz, minInputV, maxInputV, minDrive, maxDrive}) → None
                                                                                       // enters audio-to-vibe mode
    await haptic.setMode(DRV2605Full.MODE_INTERNAL_TRIGGER);                       // Select operating mode, (mode 0–7) → None
                                                                                       // back to internal trigger

    await haptic.playEffect(DRV2605Full.EFFECT_BUZZ);                              // Play a ROM effect, (effectId 1–123) → None
                                                                                       // a buzz, so the supply and LRA readings are valid
    await sleep(200);
    console.log('vdd', await haptic.readSupplyVoltage());                          // Read supply voltage, () → number V
                                                                                       // VBAT × 5.6 / 255; valid during playback
    console.log('lra', await haptic.readLraFrequency());                           // Read LRA frequency, () → number Hz | null
                                                                                       // null when LRA_PERIOD is 0
    await haptic.stop();                                                           // Cancel playback, () → None
    console.log(await haptic.readStatus());                                        // Read status flags, () → {diagFailed, fbTimeout, overTemp, overCurrent}
                                                                                       // clears the latched flags
    console.log('otp', await haptic.otpProgrammed());                              // Read OTP status, () → boolean
                                                                                       // true once the OTP has been burned
    // await haptic.programOtp(true);                                              // IRREVERSIBLE one-time OTP burn, (confirm) → None
    await haptic.standby();                                                        // Enter software standby, () → None
                                                                                       // also stops playback
    await haptic.wake();                                                           // Leave software standby, () → None
    await haptic.reset();                                                          // Reset all registers, () → None
                                                                                       // re-run init() afterwards
    await connection.close();
})();
