//! Exercises every method in the ADS1113 Full API.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::ads1113::{ADS1113_DR_250, ADS1113_I2C_ADDRESS};
use periph::chips::adc_dac::Ads1113Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut adc = Ads1113Full::new(dev, ADS1113_I2C_ADDRESS);                                   // Create ADS1113 Full driver, (i2c, addr=0x48)
                                                                                                // no bus traffic; Config word is rebuilt per conversion
    let raw = adc.read_raw(&mut delay).expect("read_raw");                                      // Read raw code, (delay) → Result<i16, E>
                                                                                                // single-shot conversion, signed 16-bit
    let v = adc.read_voltage(&mut delay).expect("read_voltage");                                // Read voltage, (delay) → Result<f32 V, E>
                                                                                                // raw × lsb()
    println!("raw {} voltage {:.5} V", raw, v);
    let fs = adc.full_scale();                                                                  // Full scale, () → f32 V
                                                                                                // fixed ±2.048 V
    let lsb = adc.lsb();                                                                        // LSB, () → f32 V
                                                                                                // full_scale() / 32768
    println!("full scale {} V, lsb {} V", fs, lsb);
    adc.set_data_rate(ADS1113_DR_250).expect("set_data_rate");                                  // Set data rate, (dr 0–7) → Result<(), E>
                                                                                                // DR field of the Config word
    let sps = adc.data_rate_sps();                                                              // Data rate, () → u16 SPS
                                                                                                // samples per second of the current setting
    println!("rate {} SPS", sps);
    adc.start_continuous().expect("start_continuous");                                          // Start continuous, () → Result<(), E>
                                                                                                // MODE ← 0
    sleep(Duration::from_millis(50));
    let craw = adc.read_continuous_raw().expect("read_continuous_raw");                         // Read continuous raw, () → Result<i16, E>
                                                                                                // CONVERSION without touching Config
    let cv = adc.read_continuous().expect("read_continuous");                                   // Read continuous, () → Result<f32 V, E>
                                                                                                // read_continuous_raw() × lsb()
    println!("continuous {} {:.5} V", craw, cv);
    adc.stop_continuous().expect("stop_continuous");                                            // Stop continuous, () → Result<(), E>
                                                                                                // MODE ← 1
    let busy = adc.is_converting().expect("is_converting");                                     // Check conversion, () → Result<bool, E>
                                                                                                // CONFIG.OS == 0
    let cfg = adc.read_config().expect("read_config");                                          // Read CONFIG, () → Result<u16, E>
                                                                                                // raw 16-bit register
    println!("converting {}, config 0x{:04X}", busy, cfg);
    adc.reset().expect("reset");                                                                // Reset, () → Result<(), E>
                                                                                                // writes the reset values back
}
