use linux_embedded_hal::Delay;
use linux_embedded_hal::I2cdev;
use periph::chips::comms::{Rda5807mFull, BAND_WORLD, SPACE_100K};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x10);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut fm = Rda5807mFull::new(dev, addr, 100.0, 8).expect("init RDA5807M");

    fm.set_frequency(97.5).unwrap();                    // Tune to frequency, (frequency_mhz) → ()
    println!("frequency: {:.2} MHz", fm.frequency().unwrap()); // Read tuned frequency, () → f32 MHz

    fm.set_volume(10).unwrap();                         // Set volume, (level 0–15) → ()
    fm.mute(false).unwrap();                            // Mute/unmute, (enable) → ()

    if let Some(freq) = fm.seek(true).unwrap() {         // Seek next station, (up=true) → Option<f32> MHz
        println!("seek found: {:.2} MHz", freq);
    }

    fm.configure(Some(BAND_WORLD), Some(SPACE_100K), Some(true), Some(8), Some(true), None, None, None).unwrap();
                                                          // Configure tuner, (band, space, de_emphasis, seek_threshold, seek_mode, clk_mode, afc_disable, east_europe_50m) → ()

    fm.set_bass_boost(true).unwrap();                    // Enable bass boost, (enable) → ()
    fm.set_mono(false).unwrap();                         // Force mono/allow stereo, (enable) → ()
    fm.set_softmute(true).unwrap();                      // Enable soft mute, (enable) → ()

    fm.enable_rds(true).unwrap();                        // Enable RDS/RBDS, (enable) → ()
    sleep(Duration::from_secs(1));
    println!("rds_ready: {}", fm.rds_ready().unwrap());  // Check RDS group ready, () → bool
    if let Some(group) = fm.read_rds_group().unwrap() {   // Read raw RDS blocks, () → Option<(u16,u16,u16,u16)>
        println!("rds group: {:?}", group);
    }

    println!("is_stereo: {}", fm.is_stereo().unwrap());  // Check stereo indicator, () → bool
    println!("is_station: {}", fm.is_station().unwrap()); // Check real station, () → bool
    println!("is_ready: {}", fm.is_ready().unwrap());    // Check tuner ready, () → bool
    println!("signal_strength: {}", fm.signal_strength().unwrap()); // Read RSSI, () → u8 0–127

    fm.standby(true, &mut delay).unwrap();                // Power down/up, (enable, delay) → ()
    sleep(Duration::from_millis(10));
    fm.standby(false, &mut delay).unwrap();

    fm.soft_reset(&mut delay).unwrap();                   // Pulse soft reset, (delay) → ()
}
