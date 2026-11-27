//! Minimal example for the RRH46410 — print the air quality every sample.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::Rrh46410Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let bus = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create RRH46410 driver, (i2c, addr=0x38) → Rrh46410Minimal
    let mut chip = Rrh46410Minimal::new(bus, addr);
    // Check product ID, start IAQ 2nd Gen mode, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Wait for the next sample, (delay) → Result<Measurement {iaq, tvoc_mg_m3, etoh_ppm, eco2_ppm, relative_iaq, sample, warmed_up}>
        match chip.read(&mut delay) {
            Ok(m) => println!("IAQ {:.1}  TVOC {:.2} mg/m3  EtOH {:.2} ppm  eCO2 {} ppm  {}", m.iaq, m.tvoc_mg_m3,
                              m.etoh_ppm, m.eco2_ppm, if m.warmed_up { "ok" } else { "warming up" }),
            Err(e) => println!("read failed: {:?}", e),
        }
    }
}
