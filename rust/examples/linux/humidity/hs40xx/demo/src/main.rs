//! Demo for the HS40xx — humidity watchdog with periodic mode and an RH-high alert.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::hs40xx::{AlertThresholds, HS40xxFull, SOURCE_RH_HIGH};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x54);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create HS40xx driver, (i2c, addr=0x54) → HS40xx
    let mut chip = HS40xxFull::new(dev, addr);

    // --- Humidity watchdog ---
    // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
    // Read the 32-bit sensor ID, (delay) → Result<u32>
    println!("ID {:#x}", chip.read_id(&mut delay).expect("read_id"));
    for _ in 0..5 {
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.2} C, {:.1} %RH", t, rh);
        sleep_ms!(2000);
    }
    // Set alert thresholds, (&AlertThresholds) → Result<()>
    chip.set_alert_thresholds(&AlertThresholds { rh_high: Some(60.0), rh_high_reset: Some(55.0), ..Default::default() }).expect("set_alert_thresholds");
    // Enable alert sources, (source, delay) → Result<()>
    chip.enable_interrupt(SOURCE_RH_HIGH, &mut delay).expect("enable_interrupt");
    // Start periodic mode, (period_s, delay) → Result<()>
    chip.start_periodic(1.0, &mut delay).expect("start_periodic");
    for _ in 0..15 {
        sleep_ms!(1000);
        // Fetch the latest periodic result, (delay) → Result<Periodic>
        let p = chip.read_periodic(&mut delay).expect("read_periodic");
        let alert = if p.status & SOURCE_RH_HIGH != 0 { "ALERT" } else { "ok" };
        println!("{:.2} C, {:.1} %RH  {}", p.temperature_c, p.humidity_pct, alert);
    }
    // Stop periodic mode, () → Result<()>
    chip.stop_periodic().expect("stop_periodic");
    // Soft reset, (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("soft_reset");
}
