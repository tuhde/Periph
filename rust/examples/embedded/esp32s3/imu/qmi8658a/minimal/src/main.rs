#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::imu::qmi8658a::{Qmi8658aMinimal};

esp_app_desc!();

const ADDR: u8 = 0x6B;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    // Create QMI8658A driver, (i2c, ADDR, delay) → Result
    let mut imu = Qmi8658aMinimal::new(i2c, ADDR, &mut delay).expect("init QMI8658A");

    loop {
        // Read 3-axis acceleration, () → Result<(f32, f32, f32)> m/s²
        let (ax, ay, az) = imu.accel().expect("read accel");
        // Read 3-axis angular rate, () → Result<(f32, f32, f32)> rad/s
        let (gx, gy, gz) = imu.gyro().expect("read gyro");
        println!("accel: {:.2} {:.2} {:.2}  gyro: {:.2} {:.2} {:.2}", ax, ay, az, gx, gy, gz);
        delay.delay_ms(100);
    }
}
