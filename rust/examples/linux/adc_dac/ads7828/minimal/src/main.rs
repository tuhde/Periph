use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{Ads7828Minimal, ADS7828_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut adc = Ads7828Minimal::new(dev, Delay, ADS7828_I2C_ADDRESS, None); // Create ADS7828 driver, (i2c, delay, addr=0x48, vref None → internal 2.5 V)
    adc.init().expect("init");                                  // Enable internal reference, () → Result<(), E>

    loop {
        let raw = adc.read_raw(0).expect("read_raw");           // Read CH0 raw code, (channel=0–7) → Result<u16, E>
        let v0 = adc.read_voltage(0).expect("read_voltage");    // Read CH0 voltage, (channel=0–7) → Result<f32, E> V
        let all = adc.read_all().expect("read_all");            // Read all eight channels, () → Result<[f32; 8], E> V
        println!("raw={} v0={:.3} V ch7={:.3} V", raw, v0, all[7]);
        sleep(Duration::from_secs(1));
    }
}
