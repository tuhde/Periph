use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Apds9930Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x39);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus"); // Open I²C bus, (/dev/i2c-N) → I2cdev
    let mut delay = Delay;
    let mut chip = Apds9930Minimal::new(dev, addr, &mut delay).expect("init");    // Construct APDS-9930 Minimal, (i2c, addr=0x39, delay) → Result<Apds9930Minimal, _>
                                                                                    // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20

    loop {
        let lx = chip.lux().expect("lux");                                          // Read ambient illuminance, () → Result<f32, _> lx
                                                                                    // IR-compensated lux via Ch0/Ch1 difference
        let p  = chip.proximity().expect("proximity");                               // Read proximity count, () → Result<u16, _> count
                                                                                    // 16-bit ADC value; higher = closer object
        println!("lux={:.1} lx  proximity={}", lx, p);
        sleep(Duration::from_secs(1));
    }
}