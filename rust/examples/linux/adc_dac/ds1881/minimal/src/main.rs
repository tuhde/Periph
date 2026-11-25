// Minimal example: set the volume of two audio channels with DS1881Minimal.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{DS1881Minimal, DS1881_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut amp = DS1881Minimal::new(dev, DS1881_I2C_ADDRESS); // Create DS1881 driver, (i2c, addr=0x28)
    amp.init(&mut Delay).expect("init"); // Apply defaults, (delay) → ()
    for db in [0.0f32, 10.0, 20.0, 40.0] {
        amp.set_attenuation(0, db).expect("set_attenuation"); // Set attenuation, (channel 0–1, db dB) → ()
        amp.set_attenuation(1, db).expect("set_attenuation"); // Set attenuation, (channel 0–1, db dB) → ()
        println!("{} {}", db, amp.get_attenuation(0).expect("get_attenuation")); // Read attenuation, (channel 0–1) → f32 dB
        sleep(Duration::from_millis(500));
    }
    amp.mute(0).expect("mute"); // Mute channel, (channel 0–1) → ()
}
