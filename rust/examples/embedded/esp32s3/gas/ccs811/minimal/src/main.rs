//! Minimal example for the CCS811 — print eCO2 and TVOC for every new sample (once per second). (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gas::Ccs811Minimal;

esp_app_desc!();

const ADDR: u8 = 0x5A;

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

    // Create CCS811 driver, (i2c, addr=0x5A) → Ccs811Minimal
    let mut chip = Ccs811Minimal::new(i2c, ADDR);
    // Check HW_ID, start application, mode 1, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Wait for the next sample, (delay) → Result<Ccs811Measurement {eco2_ppm ppm, tvoc_ppb ppb}>
        let m = chip.read(&mut delay).expect("read");
        println!("eCO2 {} ppm, TVOC {} ppb", m.eco2_ppm, m.tvoc_ppb);
    }
}
