use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Apds9960Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x39);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Apds9960Full::new(dev, addr, &mut delay).expect("init APDS9960");

    // --- Monitor ambient light with adaptive integration time ---
    // Start with the default 200 ms integration (ATIME=0xB6). When the clear
    // channel approaches saturation, halve the integration time to prevent overflow.
    let mut atime: u8 = 0xB6;
    chip.configure_als(atime, 1).unwrap();

    loop {
        while !chip.is_als_valid().unwrap() {
            sleep(Duration::from_millis(10));
        }

        let (c, r, g, b) = chip.color().unwrap();
        let lux = -0.32466 * r as f32 + 1.57837 * g as f32 + -0.73191 * b as f32;
        println!("C={} R={} G={} B={}  lux~{:.0}", c, r, g, b, lux);

        // --- Adaptive integration: reduce time when saturated ---
        if chip.is_als_saturated().unwrap() && atime < 0xFE {
            atime = atime + (255 - atime) / 2;
            if atime > 0xFE { atime = 0xFE; }
            chip.configure_als(atime, 1).unwrap();
            println!("[SATURATED — reducing integration time, ATIME=0x{:02X}]", atime);
            sleep(Duration::from_millis(250));
        }

        sleep(Duration::from_secs(1));
    }
}
