#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Bmp180Full, OSS_ULP};

esp_app_desc!();

const ADDR: u8 = 0x77;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut bmp = Bmp180Full::new(i2c, 0x77, OSS_ULP).expect("init BMP180"); // Create BMP180 driver, (transport, oss=0 ULP)

    let t0 = bmp.temperature().expect("read temperature");                   // Read temperature, () → f32 C
    let p0 = bmp.pressure().expect("read pressure");                        // Read pressure, () → f32 hPa
    let alt_ref = bmp.altitude(1013.25).expect("compute altitude");       // Compute altitude, (sea_level_hpa=1013.25) → f32 m
    println!("Reference: {:.1} C, {:.1} hPa, alt={:.1} m", t0, p0, alt_ref);
    let mut prev_alt = 0.0f32;

    for n in 0..60 {
        let t = bmp.temperature().expect("read temperature");               // Read temperature, () → f32 C
        let p = bmp.pressure().expect("read pressure");                     // Read pressure, () → f32 hPa
        let a = bmp.altitude(1013.25).expect("compute altitude");         // Compute altitude, (sea_level_hpa=1013.25) → f32 m
        let da = (a - prev_alt) * 100.0;

        if n > 0 {
            println!("{}s: {:.1} C, {:.1} hPa, alt={:.1} m (delta={:+.0} cm)", n, t, p, a, da);
        } else {
            println!("{}s: {:.1} C, {:.1} hPa, alt={:.1} m", n, t, p, a);
        }
        prev_alt = a;
        delay.delay_ms(1000);
    }
    loop {}
}
