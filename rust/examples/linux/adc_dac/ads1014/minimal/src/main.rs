use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Ads1014Minimal, ADS1014_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Ads1014Minimal::new(dev, ADS1014_I2C_ADDRESS); // Create ADS1014 driver, (i2c, addr=0x48)
    let mut delay = Delay;

    for _ in 0..10 {
        let raw = adc.read_raw(&mut delay).expect("read_raw"); // Read raw code, (delay) → i16 (−2048…2047)
        let v = adc.read_voltage(&mut delay).expect("read_voltage"); // Read voltage, (delay) → f32 V
        println!("{} -> {:.3} V", raw, v);
        sleep(Duration::from_secs(1));
    }
}
