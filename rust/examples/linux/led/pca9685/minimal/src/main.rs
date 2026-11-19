use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::led::pca9685::{PCA9685Minimal};
use std::thread::sleep;
use std::time::Duration;

fn sleep_ms(ms: u64) { sleep(Duration::from_millis(ms)); }

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;

    let mut pwm = PCA9685Minimal::new(dev, addr);               // Create PCA9685 driver, (i2c, addr=0x40)
    pwm.init(&mut delay).expect("init PCA9685");                // Sleep, set 1 kHz, wake, (delay) → ()

    for step in 0..=10 {
        pwm.set_duty(0, step as f32 / 10.0).unwrap();           // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → ()
        sleep_ms(200);
    }
    pwm.set_duty(0, 0.0).unwrap();
    println!("===DONE: 0 passed, 0 failed===");
}
