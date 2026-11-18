use linux_embedded_hal::I2cdev;
use periph::chips::led::is31fl3731::{Is31fl3731Minimal, COLS};
use std::thread::sleep;
use std::time::Duration;

fn sleep_ms(ms: u64) { sleep(Duration::from_millis(ms)); }

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x74);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    let mut matrix = Is31fl3731Minimal::new(dev, addr).expect("init IS31FL3731"); // Create IS31FL3731 driver, (i2c, addr=0x74)

    for col in 0..COLS {
        matrix.set_pixel(0, col, 128).unwrap();                 // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    }
    sleep_ms(1000);
    matrix.fill(32).unwrap();                                   // Set every LED's PWM brightness, (brightness 0–255) → ()
    sleep_ms(1000);
    matrix.clear().unwrap();                                    // Switch all LEDs off, () → ()
    println!("===DONE: 0 passed, 0 failed===");
}
