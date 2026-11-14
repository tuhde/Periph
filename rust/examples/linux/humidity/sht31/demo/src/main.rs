//! Demo for the SHT31 — repeatability levels, periodic mode, heater test, status and dew point.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::{Repeatability, Sht31Full};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x44);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create SHT31 driver, (i2c, addr=0x44, delay) → Result
    let mut chip = Sht31Full::new(dev, addr, &mut delay).expect("init");

    // Soft reset, (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("soft_reset");
    // Read status register, () → Result<u16>
    println!("status 0x{:04X}", chip.read_status().expect("status"));

    // --- Single shots at all three repeatability levels ---
    for level in [Repeatability::Low, Repeatability::Medium, Repeatability::High] {
        // Select repeatability, (level) → ()
        chip.set_repeatability(level);
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:?}: {:.2} C {:.1} %RH", level, t, rh);
    }
    // Use clock-stretching commands, (on) → ()
    chip.set_clock_stretching(false);
    // Read raw words, (delay) → Result<(u16, u16)>
    println!("raw {:?}", chip.read_raw(&mut delay).expect("raw"));

    // --- Periodic mode at 2 mps: ten samples ---
    // Start periodic mode, (rate, repeatability, art) → Result<()>
    chip.start_periodic(2.0, Repeatability::High, false).expect("start_periodic");
    let mut got = 0;
    while got < 10 {
        // Fetch the latest periodic result, () → Result<Option<(f32, f32)>>
        match chip.fetch().expect("fetch") {
            Some((t, rh)) => {
                got += 1;
                println!("periodic {}: {:.2} C {:.1} %RH", got, t, rh);
            }
            None => sleep_ms!(50),
        }
    }
    // Stop periodic mode, (delay) → Result<()>
    chip.stop_periodic(&mut delay).expect("stop_periodic");

    // --- Heater test: 10 s ---
    // Read temperature only, (delay) → Result<f32>
    let before = chip.read_temperature(&mut delay).expect("temperature");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    sleep_ms!(10000);
    println!("heater status bit: {}", chip.read_status().expect("status") & 0x2000 != 0);
    let after = chip.read_temperature(&mut delay).expect("temperature");
    println!("temperature rise: {:.2} K", after - before);
    chip.set_heater(false).expect("heater off");
    // Clear status flags, () → Result<()>
    chip.clear_status().expect("clear_status");
    println!("status 0x{:04X}", chip.read_status().expect("status"));
    // Compute dew point, (delay) → Result<f32> °C
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew_point"));
}
