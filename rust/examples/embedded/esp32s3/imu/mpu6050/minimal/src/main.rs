#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::imu::MPU6050Minimal;

esp_app_desc!();

const ADDR: u8 = 0x68;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = MPU6050Minimal::new(i2c, ADDR, &mut delay).expect("init MPU6050"); // Create MPU6050 driver, (i2c, ADDR, delay) → Result

    loop {
        let (ax, ay, az) = chip.accel().expect("accel"); // Read 3-axis acceleration, () → (f32, f32, f32) m/s²
        let (gx, gy, gz) = chip.gyro().expect("gyro");   // Read 3-axis angular rate, () → (f32, f32, f32) rad/s
        println!("accel: {:.2} {:.2} {:.2}  gyro: {:.2} {:.2} {:.2}",
                 ax, ay, az, gx, gy, gz);
        delay.delay_ms(100);
    }
}
