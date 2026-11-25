///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.motor.DRV2605Full
import it.uhde.periph.chips.motor.DRV2605Minimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, DRV2605Minimal.DEFAULT_ADDRESS)    // open I²C bus, device address, (bus, address=0x5A) → I2CConnection
try {
    def haptic = new DRV2605Full(connection, false)                                                 // construct driver and run init, (connection, lra=false) → DRV2605Full
                                                                                                    // DEV_RESET, DEVICE_ID check, leave standby, select ERM/LRA

    println("device id " + haptic.readDeviceId())                                                   // Read DEVICE_ID, () → int
                                                                                                    // bits 7:5 of STATUS; 3 for the DRV2605
    haptic.playEffect(DRV2605Minimal.EFFECT_STRONG_CLICK)                                           // Play a ROM effect, (effectId 1–123) → void
                                                                                                    // writes slot 1 and the end marker, then sets GO
    haptic.waitDone(5000)                                                                           // Wait for playback, (timeoutMs) → boolean
                                                                                                    // polls GO until the sequence finishes
    haptic.playSequence(DRV2605Full.SequenceStep.effect(DRV2605Minimal.EFFECT_STRONG_CLICK), DRV2605Full.SequenceStep.delay(100), DRV2605Full.SequenceStep.effect(DRV2605Minimal.EFFECT_BUZZ))  // Play a sequence, (steps ≤ 8) → void
                                                                                                    // effect(id) = ROM effect, delay(ms) = wait in 10 ms steps
    haptic.waitDone(5000)                                                                           // Wait for playback, (timeoutMs) → boolean
    println("playing " + haptic.isPlaying())                                                        // Check playback, () → boolean
                                                                                                    // reads the GO bit
    haptic.stop()                                                                                   // Cancel playback, () → void
                                                                                                    // clears GO; also leaves RTP mode

    haptic.setLibrary(1)                                                                            // Select ROM library, (library 0–6) → void
                                                                                                    // 0 empty, 1–5 ERM libraries A–E, 6 LRA library
    haptic.setActuator(false)                                                                       // Select actuator type, (lra) → void
                                                                                                    // sets N_ERM_LRA; recalibrate after changing it
    haptic.setOpenLoop(true)                                                                        // Select open-loop drive, (enabled) → void
                                                                                                    // ERM_OPEN_LOOP for an ERM, LRA_OPEN_LOOP for an LRA
    haptic.setBidirectional(true)                                                                   // Select bidirectional input, (enabled) → void
                                                                                                    // unidirectional is only valid closed-loop
    haptic.setRatedVoltage(3.0d)                                                                    // Set rated voltage, (volts V, lraFreqHz Hz) → void
                                                                                                    // code = V / 21.33 mV for an ERM
    haptic.setOverdriveClamp(3.0d)                                                                  // Set overdrive clamp, (volts V, lraFreqHz Hz) → void
                                                                                                    // code = V / 21.96 mV in open loop
    haptic.setRatedVoltageCode(0x3F)                                                                // Set rated voltage code, (code 0–255) → void
                                                                                                    // raw RATED_VOLTAGE register value
    haptic.setOverdriveClampCode(0x89)                                                              // Set overdrive clamp code, (code 0–255) → void
                                                                                                    // raw OD_CLAMP register value
    haptic.setDriveTime(2.0d)                                                                       // Set drive time, (ms) → void
                                                                                                    // ERM back-EMF sample period (1 ms + 0.2 ms × code)
    haptic.setFeedback(3, 1, 2)                                                                     // Set feedback control, (brakeFactor 0–7, loopGain 0–3, bemfGain 0–3) → void
                                                                                                    // keeps the actuator-type bit
    haptic.setAdvanced()                                                                            // Set CONTROL1–3 fields, (startupBoost, brakeStabilizer, sampleTime, blankingTime, idissTime, lraDriveTwice, supplyComp, noiseGate) → void
                                                                                                    // defaults match the chip's reset values
    haptic.setTimeOffsets(0, 0, 0, 0)                                                               // Set library time offsets, (overdriveMs, sustainPosMs, sustainNegMs, brakeMs) → void
                                                                                                    // ODT/SPT/SNT/BRT for open-loop ROM playback

    haptic.rtpLevel(0.5d)                                                                           // Stream an RTP amplitude, (level 0.0–1.0) → void
                                                                                                    // enters RTP mode with signed data; 0.5 → 64
    Thread.sleep(500)
    haptic.setRtpFormat(false)                                                                      // Select RTP data format, (unsigned) → void
                                                                                                    // false = signed (default)
    haptic.rtpWrite(0x3F)                                                                           // Write a raw RTP byte, (value 0–255) → void
                                                                                                    // 0x3F = half scale in signed bidirectional mode
    Thread.sleep(500)
    haptic.stop()                                                                                   // Cancel playback, () → void
                                                                                                    // zeroes RTP_INPUT and returns to internal trigger

    haptic.setMode(DRV2605Minimal.MODE_INTERNAL_TRIGGER)                                            // Select operating mode, (mode 0–7) → void
                                                                                                    // 0 internal trigger … 7 auto-calibration
    haptic.setInput(false)                                                                          // Select PWM/analog input, (analog) → void
                                                                                                    // N_PWM_ANALOG; used with mode 3
    haptic.setHiZ(false)                                                                            // Set high-impedance outputs, (enabled) → void
                                                                                                    // true floats OUT+/OUT−
    def cal = haptic.calibrate(false, 3.0d, 3.0d)                                                   // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → Calibration(ok, comp, bemf, bemfGain)
                                                                                                    // takes up to 1.2 s
    println("calibrated " + cal.ok + " " + cal.comp + " " + cal.bemf + " " + cal.bemfGain)
    def stored = haptic.getCalibration()                                                            // Read calibration results, () → Calibration(ok, comp, bemf, bemfGain)
                                                                                                    // A_CAL_COMP, A_CAL_BEMF, BEMF_GAIN
    haptic.setCalibration(stored.comp, stored.bemf, stored.bemfGain)                                // Restore calibration, (comp, bemf, bemfGain) → void
                                                                                                    // avoids recalibrating after a reset
    println("actuator ok " + haptic.diagnose())                                                     // Run actuator diagnostics, () → boolean
                                                                                                    // true if DIAG_RESULT = 0
    haptic.setAudioToVibe(true, 20, 125, 0.1d, 1.8d, 0.1d, 1.0d)                                    // Configure audio-to-vibe, (acCouple, peakTimeMs, filterHz, minInputV, maxInputV, minDrive, maxDrive) → void
                                                                                                    // enters audio-to-vibe mode
    haptic.setMode(DRV2605Minimal.MODE_INTERNAL_TRIGGER)                                            // Select operating mode, (mode 0–7) → void
                                                                                                    // back to internal trigger

    haptic.playEffect(DRV2605Minimal.EFFECT_BUZZ)                                                   // Play a ROM effect, (effectId 1–123) → void
                                                                                                    // a buzz, so the supply and LRA readings are valid
    Thread.sleep(200)
    println("vdd " + haptic.readSupplyVoltage() + " V")                                             // Read supply voltage, () → double V
                                                                                                    // VBAT × 5.6 / 255; valid during playback
    println("lra " + haptic.readLraFrequency())                                                     // Read LRA frequency, () → OptionalDouble Hz (empty if LRA_PERIOD is 0)
                                                                                                    // valid only during LRA playback
    haptic.stop()                                                                                   // Cancel playback, () → void
    println("status " + haptic.readStatus())                                                        // Read status flags, () → Status(diagFailed, fbTimeout, overTemp, overCurrent)
                                                                                                    // clears the latched flags
    println("otp " + haptic.otpProgrammed())                                                        // Read OTP status, () → boolean
                                                                                                    // true once the OTP has been burned
    // haptic.programOtp(true); // IRREVERSIBLE one-time OTP burn, (confirm) → void
    haptic.standby()                                                                                // Enter software standby, () → void
                                                                                                    // also stops playback
    haptic.wake()                                                                                   // Leave software standby, () → void
    haptic.reset()                                                                                  // Reset all registers, () → void
                                                                                                    // re-create the driver afterwards
} finally {
    connection.close()
}
