#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::memory::{At24c64Minimal};

esp_app_desc!();

const ADDR: u8 = 0x50;

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

    // Create AT24C64 driver, (i2c, ADDR=0x50) → Self
    let mut chip = At24c64Minimal::new(i2c, ADDR);

    let mut counter: u8 = 0;
    loop {
        // Write a single byte, (address 0–8191, value 0–255, delay) → Result<()>
        chip.write_byte(0x0000, counter, &mut delay).expect("write_byte");
        // Read a single byte, (address 0–8191) → Result<u8>
        let value = chip.read_byte(0x0000).expect("read_byte");
        println!("Stored: {}", value);
        counter = counter.wrapping_add(1);
        sleep_ms!(1000);
    }
}
