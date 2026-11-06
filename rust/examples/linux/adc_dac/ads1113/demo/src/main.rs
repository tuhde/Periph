//! Demo for the ADS1113 — a real-world scenario (see the section comments below).

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::ads1113::{ADS1113_DR_8, ADS1113_I2C_ADDRESS};
use periph::chips::adc_dac::Ads1113Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
let mut adc = Ads1113Full::new(dev, ADS1113_I2C_ADDRESS);                                       // Create ADS1113 Full driver, (i2c, addr=0x48)

    // --- Configure for low-noise slow sampling ---
    // 8 SPS gives the longest integration time per conversion, which
    // suppresses mains and switching noise on a slowly varying voltage.
    adc.set_data_rate(ADS1113_DR_8).expect("set_data_rate");                                    // Set data rate, (dr 0–7) → Result<(), E>

    // --- Log single-shot readings ---
    // Each read_voltage() wakes the chip, converts and powers down again.
    for _ in 0..5 {
        let v = adc.read_voltage(&mut delay).expect("read_voltage");                            // Read voltage, (delay) → Result<f32 V, E>
        println!("{:.5} V", v);
        sleep(Duration::from_millis(1000));
    }

    // --- Average continuous readings ---
    // Back-to-back conversions averaged over 1.25 s reduce random noise.
    adc.start_continuous().expect("start_continuous");                                          // Start continuous, () → Result<(), E>
    sleep(Duration::from_millis(200));
    let mut acc = 0.0f32;
    for _ in 0..10 {
        acc += adc.read_continuous().expect("read_continuous");                                 // Read continuous, () → Result<f32 V, E>
        sleep(Duration::from_millis(125));
    }
    adc.stop_continuous().expect("stop_continuous");                                            // Stop continuous, () → Result<(), E>
    println!("average {:.5} V", acc / 10.0);
}
