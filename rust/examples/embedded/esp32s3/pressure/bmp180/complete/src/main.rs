#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Bmp180Full, OSS_STANDARD};

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

    let mut bmp = Bmp180Full::new(i2c, 0x77, 0).expect("init BMP180"); // Create BMP180 driver, (connection, oss=0)
    let cid = bmp.chip_id().expect("read chip id");                    // Read chip ID, () → u8
    println!("chip_id=0x{:02x}", cid);                                  // returns 0x55 for BMP180
    let oss = bmp.oversampling();                                      // Read OSS, () → u8 0–3
    println!("oss={}", oss);
    bmp.set_oversampling(OSS_STANDARD);                                // Set OSS, (oss 0–3) → ()
                                                                     // changes conversion time vs resolution trade-off
    let t = bmp.temperature().expect("read temperature");             // Read temperature, () → f32 C
    let p = bmp.pressure().expect("read pressure");                   // Read pressure, () → f32 hPa
    let alt = bmp.altitude(1013.25).expect("compute altitude");       // Compute altitude, (sea_level_hpa=1013.25) → f32 m
                                                                     // uses barometric formula to convert pressure to metres
    let slp = bmp.sea_level_pressure(alt).expect("compute slp");       // Compute sea-level pressure, (altitude_m) → f32 hPa
    bmp.reset().expect("soft reset");                                  // Soft reset chip, () → ()
                                                                     // re-reads calibration after reset
    println!("T={:.1} C, P={:.1} hPa, alt={:.1} m, slp={:.1} hPa", t, p, alt, slp);
    loop {}
}
