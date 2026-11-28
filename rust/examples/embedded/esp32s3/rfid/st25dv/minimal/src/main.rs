//! Minimal example for the ST25DV — write "Hello NFC" and read it back every second.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::rfid::{St25dvMinimal};

esp_app_desc!();

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

    // Create ST25DV driver, (i2c, delay, user_addr=0x53, system_addr=0x57) → Self
    // the chip answers on two addresses on the same bus
    let mut chip = St25dvMinimal::new(i2c, Delay::new(), 0x53, 0x57);

    // Read MEM_SIZE, BLK_SIZE, IC_REF, () → Result<()>
    chip.init().expect("init");
    // Write user memory, (address, data) → Result<()>
    chip.write(0x0100, b"Hello NFC").expect("write");
    let mut buf = [0u8; 9];
    loop {
        // Read user memory, (address, buf) → Result<()>
        chip.read(0x0100, &mut buf).expect("read");
        println!("Memory: {:02X?}", buf);
        sleep_ms!(1000);
    }
}
