//! Minimal example for the AS7262.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::color::AS7262Minimal;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x49);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create AS7262 driver, (i2c, addr=0x49) → AS7262
    let mut chip = AS7262Minimal::new(dev, addr);
    // Reset and configure defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Read six calibrated channels, (delay) → Result<Channels<f32>>
        let c = chip.read(&mut delay).expect("read");
        println!("V {:.1} B {:.1} G {:.1} Y {:.1} O {:.1} R {:.1}", c.violet, c.blue, c.green, c.yellow, c.orange, c.red);
        // Read device temperature, (delay) → Result<f32 °C>
        println!("{:.0} C", chip.temperature(&mut delay).expect("temperature"));
        sleep_ms!(2000);
    }
}
