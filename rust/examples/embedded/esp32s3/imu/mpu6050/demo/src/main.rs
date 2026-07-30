#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::imu::MPU6050Full;

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

    // --- Configure for motion logging with moderate dynamic range ---
    // ±4g captures typical tilting and handling forces without clipping;
    // ±500 dps covers fast rotations while retaining sub-degree resolution.
    let mut chip = MPU6050Full::new(i2c, ADDR, &mut delay).expect("init MPU6050"); // Create MPU6050 driver, (i2c, ADDR, delay) → Result
    chip.configure_accel(1).expect("configure_accel");    // Configure accel range, (full_scale=0) → Result
    chip.configure_gyro(1).expect("configure_gyro");      // Configure gyro range, (full_scale=0) → Result

    println!("{:<8} {:<8} {:<10} {:<10}", "roll", "pitch", "|accel|", "|gyro|");

    loop {
        // gate reads on data_ready so each sample reflects a fresh conversion
        // gyro magnitude indicates how fast the board is being rotated.
        let roll  = libm::atan2f(ay, az) * 180.0 / core::f32::consts::PI;
        let pitch = libm::atan2f(-ax, libm::sqrtf(ay * ay + az * az)) * 180.0 / core::f32::consts::PI;
        let accel_mag = libm::sqrtf(ax * ax + ay * ay + az * az);
        let gyro_mag  = libm::sqrtf(gx * gx + gy * gy + gz * gz);

        println!("{:<8.1} {:<8.1} {:<10.3} {:<10.3}", roll, pitch, accel_mag, gyro_mag);
        delay.delay_ms(100);
    }
}
