use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::{AD5243Minimal, AD5243_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = AD5243Minimal::new(dev, AD5243_I2C_ADDRESS, 10000.0, 160.0); // Create AD5243 driver, (i2c, addr=0x2F, r_ab=10000.0 Ω, r_wiper=160.0 Ω)

    for code in [0, 64, 128, 192, 255] {
        pot.set_wiper(0, code).expect("set_wiper"); // Set wiper position, (channel 0–1, code 0–255) → ()
        println!("{} {}", code, pot.get_wiper(0).expect("get_wiper")); // Read wiper position, (channel 0–1) → u8 0–255
        println!("{}", pot.get_resistance(0).expect("get_resistance")); // Read W–B resistance, (channel 0–1) → f32 Ω
        sleep(Duration::from_millis(500));
    }

    pot.set_resistance(1, 2200.0).expect("set_resistance"); // Set W–B resistance, (channel 0–1, ohms Ω) → ()
}
