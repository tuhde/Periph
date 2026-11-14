#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::Si7021Minimal;

esp_app_desc!();

const ADDR: u8 = 0x40;

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

    // Create Si7021 driver, (i2c, ADDR=0x40) → Si7021
    let mut chip = Si7021Minimal::new(i2c, ADDR);

    loop {
        // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.2} C, {:.1} %RH", t, rh);
        sleep_ms!(2000);
    }
}
