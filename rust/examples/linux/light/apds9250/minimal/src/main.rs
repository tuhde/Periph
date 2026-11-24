//! Minimal example for the APDS-9250 — print red, green, blue and IR counts.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{Apds9250Minimal, APDS9250_I2C_ADDRESS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(APDS9250_I2C_ADDRESS);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create APDS-9250 driver, (i2c, addr=0x52) → Apds9250Minimal
    let mut chip = Apds9250Minimal::new(dev, addr);
    // Check PART_ID and start colour mode, (delay) → Result<(), Apds9250Error>
    chip.init(&mut delay).expect("init");

    loop {
        // Read the four channels, (delay) → Result<Apds9250Counts>
        let c = chip.read(&mut delay).expect("read");
        println!("R {} G {} B {} IR {}", c.red, c.green, c.blue, c.ir);
        sleep_ms!(1000);
    }
}
