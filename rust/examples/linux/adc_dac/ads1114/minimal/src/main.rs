use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::ads1114::{ADS1114_I2C_ADDRESS};
use periph::chips::adc_dac::Ads1114Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
let mut adc = Ads1114Minimal::new(dev, ADS1114_I2C_ADDRESS);                                    // Create ADS1114 driver, (i2c, addr=0x48)

    loop {
        let v = adc.read_voltage(&mut delay).expect("read_voltage");                            // Read voltage, (delay) → Result<f32 V, E>
        println!("{:.5} V", v);
        sleep(Duration::from_millis(1000));
    }
}
