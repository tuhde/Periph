#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::imu::bno055::Bno055Minimal;

esp_app_desc!();

const ADDR: u8 = 0x29;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create BNO055 driver, (i2c, ADDR, delay) → Result
    let mut imu = Bno055Minimal::new(i2c, ADDR, &mut delay).expect("init");

    loop {
        // Read fused Euler angles, () → Result<(f32, f32, f32)> rad
        let (h, r, p) = imu.euler().expect("read euler");
        println!("heading {:.2} rad, roll {:.2}, pitch {:.2}", h, r, p);
        sleep_ms!(100);
    }
}
