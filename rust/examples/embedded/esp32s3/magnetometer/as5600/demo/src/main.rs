#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::magnetometer::As5600Full;

esp_app_desc!();

const ADDR: u8 = 0x36;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = As5600Full::new(i2c, ADDR).expect("init AS5600");

    // --- Motor feedback monitor: read angle 10 times per second ---
    // AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
    // In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.

    let mut prev_status = chip.status_byte().unwrap();

    for _n in 0..10 {
        let a = chip.angle().unwrap();                   // Read absolute angle, () → f32 degrees
        let r = chip.raw_angle().unwrap();               // Read raw unscaled angle, () → u16 0-4095
        let g = chip.agc().unwrap();                     // Read AGC value, () → u8

        // --- Check for status changes (magnet inserted/removed) ---
        let status = chip.status_byte().unwrap();
        if status != prev_status {
            if !chip.is_magnet_detected().unwrap() {
                println!("[MAGNET REMOVED] MD=0");
            } else {
                println!("[MAGNET DETECTED] MD=1  MH={}  ML={}",
                    chip.is_magnet_too_strong().unwrap(),
                    chip.is_magnet_too_weak().unwrap());
            }
            prev_status = status;
        }

        // --- AGC health check ---
        if chip.is_magnet_detected().unwrap() {
            let tag = if g < 64 || g > 192 {
                "[AGC low — magnet weak or too far]"
            } else {
                "[OK]"
            };
            println!("angle={:.2}°  raw={}  agc={}  {}", a, r, g, tag);
        }

        delay.delay_ms(100);
    }
    loop {}
}
