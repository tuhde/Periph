#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gyroscope::L3g4200dMinimal;

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

    let mut gyro = L3g4200dMinimal::new(i2c, ADDR, false).expect("init L3G4200D"); // Create L3G4200D driver, (i2c, ADDR=0x68)

    for _ in 0..10 {
        let (x, y, z) = gyro.angular_rate().expect("angular_rate");                  // Read X/Y/Z angular rate, () → (f32, f32, f32) rad/s
        println!("X={:.3} Y={:.3} Z={:.3} rad/s", x, y, z);
        delay.delay_ms(100);
    }
    loop {}
}
