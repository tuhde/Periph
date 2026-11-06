// Haptic notification showcase: checks the actuator with diagnostics,
// calibrates it, plays a strong, double and triple click, a "heartbeat"
// sequence, then a 2-second amplitude ramp while printing the supply voltage
// (and LRA frequency). Finishes by reading the status flags.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::motor::{
    Drv2605Full, Drv2605SequenceStep, DRV2605_EFFECT_DOUBLE_CLICK, DRV2605_EFFECT_STRONG_CLICK,
    DRV2605_EFFECT_TRIPLE_CLICK,
};
use std::thread::sleep;
use std::time::Duration;

const LRA: bool = false; // set to true for a linear resonant actuator

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut haptic = Drv2605Full::new(dev, &mut delay, LRA).expect("init DRV2605"); // Create DRV2605 Full driver, (i2c, delay, lra=false)

    // --- Verify and calibrate the actuator ---
    // diagnose() reports a missing or shorted actuator; calibrate() measures the
    // back-EMF so the closed-loop engine matches this particular motor.
    println!("actuator ok: {}", haptic.diagnose(&mut delay).expect("diagnose")); // Run actuator diagnostics, (delay) → bool
    let cal = haptic.calibrate(&mut delay, LRA, 3.0, 3.0, if LRA { Some(175.0) } else { None }).expect("calibrate"); // Run auto-calibration, (delay, lra, rated_voltage V, overdrive_voltage V, lra_freq_hz Hz) → Calibration
    println!("calibration: {:?}", cal);

    // --- Play the three click effects ---
    for effect in [DRV2605_EFFECT_STRONG_CLICK, DRV2605_EFFECT_DOUBLE_CLICK, DRV2605_EFFECT_TRIPLE_CLICK] {
        haptic.play_effect(effect).expect("play"); // Play a ROM effect, (effect_id 1–123) → ()
        haptic.wait_done(&mut delay, 5000).expect("wait"); // Wait for playback, (delay, timeout_ms) → bool
        sleep(Duration::from_millis(500));
    }

    // --- Heartbeat: two strong clicks 100 ms apart ---
    // The sequencer plays both effects and the delay without host involvement.
    haptic
        .play_sequence(&[Drv2605SequenceStep::Effect(DRV2605_EFFECT_STRONG_CLICK), Drv2605SequenceStep::Wait(100), Drv2605SequenceStep::Effect(DRV2605_EFFECT_STRONG_CLICK)])
        .expect("sequence"); // Play a sequence, (items ≤ 8) → ()
    haptic.wait_done(&mut delay, 5000).expect("wait"); // Wait for playback, (delay, timeout_ms) → bool
    sleep(Duration::from_millis(500));

    // --- Two-second amplitude ramp ---
    // Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
    // while the actuator is driven.
    for step in 0..=20 {
        haptic.rtp_level(step as f32 / 20.0).expect("rtp"); // Stream an RTP amplitude, (level 0.0–1.0) → ()
        sleep(Duration::from_millis(100));
        println!("vdd {:.2} V", haptic.read_supply_voltage().expect("vdd")); // Read supply voltage, () → f32 V
        if LRA {
            println!("lra {:?}", haptic.read_lra_frequency().expect("lra")); // Read LRA frequency, () → Option<f32> Hz
        }
    }
    haptic.stop().expect("stop"); // Cancel playback, () → ()

    // --- Report latched faults ---
    println!("status: {:?}", haptic.read_status().expect("status")); // Read status flags, () → Status
}
