//! Minimal example for the RRH62000 — print the air quality every sample. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
#[allow(unused_imports)]
use esp_println::{print, println};
use periph::chips::gas::Rrh62000Minimal;

esp_app_desc!();

const ADDR: u8 = 0x69;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let bus = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let addr = ADDR;

    // Create RRH62000 driver, (i2c, delay, addr=0x69) → Rrh62000Minimal
    let mut chip = Rrh62000Minimal::new(bus, delay, addr);
    // Wait for start-up, check firmware version, () → Result<()>
    chip.init().expect("init");

    loop {
        // Wait for a new sample, () → Result<Measurement {pm1_ug_m3, pm2_5_ug_m3, pm10_ug_m3 µg/m³, temperature_c °C, humidity_rh %RH, tvoc µg/m³, eco2_ppm ppm, iaq}>
        match chip.read() {
            Ok(m) => println!("PM1 {:.1}  PM2.5 {:.1}  PM10 {:.1} ug/m3  {:.2} C  {:.2} %RH  TVOC {} ug/m3  eCO2 {} ppm  IAQ {:.2}",
                              m.pm1_ug_m3, m.pm2_5_ug_m3, m.pm10_ug_m3, m.temperature_c, m.humidity_rh, m.tvoc, m.eco2_ppm, m.iaq),
            Err(e) => println!("read failed: {:?}", e),
        }
    }
}
