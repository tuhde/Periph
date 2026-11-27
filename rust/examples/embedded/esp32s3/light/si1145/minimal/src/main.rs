//! Minimal example for the Si1145 — proximity, UV index and light once per second. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
#[allow(unused_imports)]
use esp_println::{print, println};
use periph::chips::light::Si1145Minimal;

esp_app_desc!();

const ADDR: u8 = 0x60;

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

    // Create Si1145 driver, (i2c, addr=0x60) → Si1145Minimal
    let mut sensor = Si1145Minimal::new(bus, addr);
    // Check PART_ID, HW_KEY, UV + ALS + PS1, (delay) → Result<()>
    sensor.init(&mut delay).expect("init");

    loop {
        // Forced PS + ALS, (delay) → Result<Reading {proximity, uv_index, visible, infrared}>
        let r = sensor.read(&mut delay).expect("read");
        println!("prox {}  UV {:.2}  vis {}  ir {}", r.proximity, r.uv_index, r.visible, r.infrared);
        sleep_ms!(1000);
    }
}
