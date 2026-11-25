use linux_embedded_hal::I2cdev;
use periph::chips::adc_dac::{CAT5171Minimal, CAT5171_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = CAT5171Minimal::new(dev, CAT5171_I2C_ADDRESS, 100000.0, 50.0); // Create CAT5171 driver, (i2c, addr=0x2C, r_ab=100000.0 Ω, r_wiper=50.0 Ω)

    for code in [0, 64, 128, 192, 255] {
        pot.set_wiper(code).expect("set_wiper"); // Set wiper position, (code 0–255) → ()
        println!("{} {}", code, pot.get_wiper().expect("get_wiper")); // Read wiper position, () → u8 0–255
        println!("{}", pot.get_resistance().expect("get_resistance")); // Read W–B resistance, () → f32 Ω
        sleep(Duration::from_millis(500));
    }

    pot.set_resistance(22000.0).expect("set_resistance"); // Set W–B resistance, (ohms Ω) → ()
}
