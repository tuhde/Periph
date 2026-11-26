//! Minimal example for the CCS811 — print eCO2 and TVOC for every new sample (once per second).

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::Ccs811Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create CCS811 driver, (i2c, addr=0x5A) → Ccs811Minimal
    let mut chip = Ccs811Minimal::new(dev, addr);
    // Check HW_ID, start application, mode 1, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Wait for the next sample, (delay) → Result<Ccs811Measurement {eco2_ppm ppm, tvoc_ppb ppb}>
        let m = chip.read(&mut delay).expect("read");
        println!("eCO2 {} ppm, TVOC {} ppb", m.eco2_ppm, m.tvoc_ppb);
    }
}
