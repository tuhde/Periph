//! Minimal example for the RRH62000 — print the air quality every sample.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::Rrh62000Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x69);

    let bus = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create RRH62000 driver, (i2c, delay, addr=0x69) → Rrh62000Minimal
    let mut chip = Rrh62000Minimal::new(bus, delay, addr);
    // Wait for start-up, check firmware version, () → Result<()>
    chip.init().expect("init");

    loop {
        // Wait for a new sample, () → Result<Measurement {pm1_ug_m3, pm2_5_ug_m3, pm10_ug_m3 µg/m³, temperature_c °C, humidity_rh %RH, tvoc µg/m³, eco2_ppm ppm, iaq}>
        match chip.read() {
            Ok(m) => println!("PM1 {:.1}  PM2.5 {:.1}  PM10 {:.1} ug/m3  {:.2} C  {:.2} %RH  TVOC {} ug/m3  eCO2 {} ppm  IAQ {:.2}",
                              m.pm1_ug_m3, m.pm2_5_ug_m3, m.pm10_ug_m3, m.temperature_c, m.humidity_rh, m.tvoc, m.eco2_ppm, m.iaq),
            Err(e) => println!("read failed: {:?}", e),
        }
    }
}
