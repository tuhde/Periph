use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::other::Mpr121Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus"); // Open I²C bus, (/dev/i2c-N) → I2cdev
    let mut delay = Delay;
    let mut chip = Mpr121Minimal::new(dev, addr, &mut delay).expect("init");       // Construct MPR121 Minimal, (i2c, addr=0x5A, delay) → Result<Mpr121Minimal, _>
                                                                                   // resets, applies default thresholds (T=12, R=6), enters Run Mode on all 12 electrodes

    loop {
        let t = chip.touched().expect("touched");                                    // Read 12-bit touch bitmask, () → Result<u16, _> bitmask
                                                                                   // bit n=1 means ELEn is currently touched
        println!("touched=0x{:03X}", t);
        sleep(Duration::from_secs(1));
    }
}
