// Demo: stereo volume control with mute and optional EEPROM store.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{DS1881Full, DS1881_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let store = std::env::args().any(|a| a == "--store");

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut amp = DS1881Full::new(dev, DS1881_I2C_ADDRESS); // Create DS1881 Full driver, (i2c, addr=0x28)
    amp.init(&mut Delay).expect("init"); // Apply defaults, (delay) → ()

    // --- Fade the volume down and back up ---
    // Zero-crossing detection (on by default) moves the wiper only at a signal
    // zero crossing, so each 2 dB step is clickless; 100 ms per step is longer
    // than the 50 ms maximum zero-crossing window.
    let ramp = (0..=20).map(|i| i * 2).chain((0..20).rev().map(|i| i * 2));
    for db in ramp {
        amp.set_attenuation_both(db as f32, None).expect("set_attenuation_both"); // Set both attenuations, (db0 dB, db1=None dB) → ()
        let (p0, p1, _) = amp.read_all().expect("read_all"); // Read everything, () → (u8, u8, u8)
        println!("{:2} dB -> positions {} {}", db, p0, p1);
        sleep(Duration::from_millis(100));
    }

    // --- Mute and unmute one channel ---
    // Muting channel 0 leaves channel 1 playing; restoring writes the position again.
    amp.mute(0).expect("mute"); // Mute channel, (channel 0–1) → ()
    println!("channel 0 muted: {}", amp.get_attenuation(0).expect("get_attenuation")); // Read attenuation, (channel 0–1) → f32 dB
    sleep(Duration::from_secs(1));
    amp.set_attenuation(0, 0.0).expect("set_attenuation"); // Set attenuation, (channel 0–1, db dB) → ()

    // --- Inspect the Option 2 attenuation table ---
    // Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
    // numeric value when the option changes, so re-set them afterwards.
    amp.set_option(2).expect("set_option"); // Select option, (option 1–2) → ()
    for position in [0u8, 12, 13, 24, 25, 32, 33] {
        println!("position {} -> {} dB", position, amp.position_to_db(position)); // Table lookup, (position) → f32 dB
    }
    amp.set_option(1).expect("set_option"); // Select option, (option 1–2) → ()
    amp.set_attenuation_both(10.0, None).expect("set_attenuation_both"); // Set both attenuations, (db0 dB, db1=None dB) → ()
    println!("{:?}", amp.get_config().expect("get_config")); // Read configuration, () → Ds1881Config {option, zero_crossing, nonvolatile, raw}

    // --- Optionally persist the volume ---
    // Stored positions are restored at power-up. The EEPROM is rated for only
    // 50 000 writes, so this runs only when requested with --store.
    if store {
        println!("WARNING: EEPROM endurance is 50 000 writes");
        amp.set_nonvolatile(true).expect("set_nonvolatile"); // Select EEPROM storage, (enabled bool) → ()
        let start = std::time::Instant::now();
        amp.store().expect("store"); // Store positions, () → ()
        println!("store took {:?}", start.elapsed());
        amp.set_nonvolatile(false).expect("set_nonvolatile"); // Select EEPROM storage, (enabled bool) → ()
    }
}
