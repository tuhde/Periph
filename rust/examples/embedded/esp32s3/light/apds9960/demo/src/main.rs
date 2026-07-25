#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::Apds9960Full;

esp_app_desc!();

const ADDR: u8 = 0x39;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Apds9960Full::new(i2c, ADDR, &mut delay).expect("init APDS9960");

    // --- Monitor ambient light with adaptive integration time ---
    // Start with the default 200 ms integration (ATIME=0xB6). When the clear
    // channel approaches saturation, halve the integration time to prevent overflow.
    let mut atime: u8 = 0xB6;
    chip.configure_als(atime, 1).unwrap();

    loop {
        while !chip.is_als_valid().unwrap() {
            delay.delay_ms(10);
        }

        let (c, r, g, b) = chip.color().unwrap();
        let lux = -0.32466 * r as f32 + 1.57837 * g as f32 + -0.73191 * b as f32;
        println!("C={} R={} G={} B={}  lux~{:.0}", c, r, g, b, lux);

        // --- Adaptive integration: reduce time when saturated ---
        if chip.is_als_saturated().unwrap() && atime < 0xFE {
            atime = atime + (255 - atime) / 2;
            if atime > 0xFE { atime = 0xFE; }
            chip.configure_als(atime, 1).unwrap();
            println!("[SATURATED — reducing integration time, ATIME=0x{:02X}]", atime);
            delay.delay_ms(250);
        }

        delay.delay_ms(1000);
    }
}
