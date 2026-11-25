use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::motor::{
    Drv2605AdvancedConfig, Drv2605AudioToVibeConfig, Drv2605Full, Drv2605SequenceStep,
    DRV2605_EFFECT_BUZZ, DRV2605_EFFECT_STRONG_CLICK, DRV2605_MODE_INTERNAL_TRIGGER,
};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut haptic = Drv2605Full::new(dev, &mut delay, false).expect("init DRV2605"); // Create DRV2605 Full driver, (i2c, delay, lra=false)

    println!("device id {}", haptic.read_device_id().expect("id")); // Read DEVICE_ID, () → u8
                                                                    // bits 7:5 of STATUS; 3 for the DRV2605
    haptic.play_effect(DRV2605_EFFECT_STRONG_CLICK).expect("play"); // Play a ROM effect, (effect_id 1–123) → ()
                                                                    // writes slot 1 and the end marker, then sets GO
    haptic.wait_done(&mut delay, 5000).expect("wait"); // Wait for playback, (delay, timeout_ms) → bool
                                                       // polls GO until the sequence finishes
    haptic
        .play_sequence(&[Drv2605SequenceStep::Effect(DRV2605_EFFECT_STRONG_CLICK), Drv2605SequenceStep::Wait(100), Drv2605SequenceStep::Effect(DRV2605_EFFECT_BUZZ)])
        .expect("sequence"); // Play a sequence, (items ≤ 8) → ()
                             // Effect = ROM effect, Wait = delay in 10 ms steps
    haptic.wait_done(&mut delay, 5000).expect("wait"); // Wait for playback, (delay, timeout_ms) → bool
    println!("playing {}", haptic.is_playing().expect("playing")); // Check playback, () → bool
                                                                   // reads the GO bit
    haptic.stop().expect("stop"); // Cancel playback, () → ()
                                  // clears GO; also leaves RTP mode

    haptic.set_library(1).expect("library"); // Select ROM library, (library 0–6) → ()
                                             // 0 empty, 1–5 ERM libraries A–E, 6 LRA library
    haptic.set_actuator(false).expect("actuator"); // Select actuator type, (lra) → ()
                                                   // sets N_ERM_LRA; recalibrate after changing it
    haptic.set_open_loop(true).expect("open loop"); // Select open-loop drive, (enabled) → ()
                                                    // ERM_OPEN_LOOP for an ERM, LRA_OPEN_LOOP for an LRA
    haptic.set_bidirectional(true).expect("bidir"); // Select bidirectional input, (enabled) → ()
                                                    // unidirectional is only valid closed-loop
    haptic.set_rated_voltage(3.0, None).expect("rated"); // Set rated voltage, (volts V, lra_freq_hz Hz) → ()
                                                         // code = V / 21.33 mV for an ERM
    haptic.set_overdrive_clamp(3.0, None).expect("clamp"); // Set overdrive clamp, (volts V, lra_freq_hz Hz) → ()
                                                           // code = V / 21.96 mV in open loop
    haptic.set_rated_voltage_code(0x3F).expect("rated code"); // Set rated voltage code, (code 0–255) → ()
                                                              // raw RATED_VOLTAGE register value
    haptic.set_overdrive_clamp_code(0x89).expect("clamp code"); // Set overdrive clamp code, (code 0–255) → ()
                                                                // raw OD_CLAMP register value
    haptic.set_drive_time(2.0).expect("drive time"); // Set drive time, (ms) → ()
                                                     // ERM back-EMF sample period (1 ms + 0.2 ms × code)
    haptic.set_feedback(3, 1, 2).expect("feedback"); // Set feedback control, (brake_factor 0–7, loop_gain 0–3, bemf_gain 0–3) → ()
                                                     // keeps the actuator-type bit
    haptic.set_advanced(&Drv2605AdvancedConfig::default()).expect("advanced"); // Set CONTROL1–3 fields, (AdvancedConfig) → ()
                                                                               // defaults match the chip's reset values
    haptic.set_time_offsets(0, 0, 0, 0).expect("offsets"); // Set library time offsets, (overdrive_ms, sustain_pos_ms, sustain_neg_ms, brake_ms) → ()
                                                           // ODT/SPT/SNT/BRT for open-loop ROM playback

    haptic.rtp_level(0.5).expect("rtp"); // Stream an RTP amplitude, (level 0.0–1.0) → ()
                                         // enters RTP mode with signed data; 0.5 → 64
    sleep(Duration::from_millis(500));
    haptic.set_rtp_format(false).expect("rtp format"); // Select RTP data format, (unsigned) → ()
                                                       // false = signed (default)
    haptic.rtp_write(0x3F).expect("rtp write"); // Write a raw RTP byte, (value 0–255) → ()
                                                // 0x3F = half scale in signed bidirectional mode
    sleep(Duration::from_millis(500));
    haptic.stop().expect("stop"); // Cancel playback, () → ()
                                  // zeroes RTP_INPUT and returns to internal trigger

    haptic.set_mode(DRV2605_MODE_INTERNAL_TRIGGER).expect("mode"); // Select operating mode, (mode 0–7) → ()
                                                                   // 0 internal trigger … 7 auto-calibration
    haptic.set_input(false).expect("input"); // Select PWM/analog input, (analog) → ()
                                             // N_PWM_ANALOG; used with mode 3
    haptic.set_hi_z(false).expect("hi-z"); // Set high-impedance outputs, (enabled) → ()
                                           // true floats OUT+/OUT−
    let cal = haptic.calibrate(&mut delay, false, 3.0, 3.0, None).expect("calibrate"); // Run auto-calibration, (delay, lra, rated_voltage V, overdrive_voltage V, lra_freq_hz Hz) → Calibration
                                                                                       // takes up to 1.2 s
    println!("calibrated {:?}", cal);
    let stored = haptic.get_calibration().expect("get_calibration"); // Read calibration results, () → Calibration
                                                                     // A_CAL_COMP, A_CAL_BEMF, BEMF_GAIN
    haptic.set_calibration(stored.comp, stored.bemf, stored.bemf_gain).expect("set_calibration"); // Restore calibration, (comp, bemf, bemf_gain) → ()
                                                                                                   // avoids recalibrating after a reset
    println!("actuator ok {}", haptic.diagnose(&mut delay).expect("diagnose")); // Run actuator diagnostics, (delay) → bool
                                                                                // true if DIAG_RESULT = 0
    haptic.set_audio_to_vibe(&Drv2605AudioToVibeConfig::default()).expect("audio"); // Configure audio-to-vibe, (AudioToVibeConfig) → ()
                                                                                    // enters audio-to-vibe mode
    haptic.set_mode(DRV2605_MODE_INTERNAL_TRIGGER).expect("mode"); // Select operating mode, (mode 0–7) → ()
                                                                   // back to internal trigger

    haptic.play_effect(DRV2605_EFFECT_BUZZ).expect("play"); // Play a ROM effect, (effect_id 1–123) → ()
                                                            // a buzz, so the supply and LRA readings are valid
    sleep(Duration::from_millis(200));
    println!("vdd {:.2} V", haptic.read_supply_voltage().expect("vdd")); // Read supply voltage, () → f32 V
                                                                         // VBAT × 5.6 / 255; valid during playback
    println!("lra {:?}", haptic.read_lra_frequency().expect("lra")); // Read LRA frequency, () → Option<f32> Hz
                                                                     // None when LRA_PERIOD is 0
    haptic.stop().expect("stop"); // Cancel playback, () → ()
    println!("{:?}", haptic.read_status().expect("status")); // Read status flags, () → Status
                                                            // diag_failed, fb_timeout, over_temp, over_current; clears the latches
    println!("otp {}", haptic.otp_programmed().expect("otp")); // Read OTP status, () → bool
                                                               // true once the OTP has been burned
    // haptic.program_otp(&mut delay, true)?;           // IRREVERSIBLE one-time OTP burn, (delay, confirm) → ()
    haptic.standby().expect("standby"); // Enter software standby, () → ()
                                        // also stops playback
    haptic.wake().expect("wake"); // Leave software standby, () → ()
    haptic.reset(&mut delay).expect("reset"); // Reset all registers, (delay) → ()
                                              // re-create the driver afterwards
}
