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

    // --- Status and magnet checks ---
    println!("{}", chip.is_magnet_detected().unwrap());    // Check magnet present, () → bool
    println!("{}", chip.is_magnet_too_strong().unwrap());  // Check magnet too strong, () → bool
    println!("{}", chip.is_magnet_too_weak().unwrap());    // Check magnet too weak, () → bool
    println!("0x{:02X}", chip.status_byte().unwrap());     // Read raw status, () → u8

    // --- Angle readings ---
    println!("{}", chip.angle().unwrap());                 // Read absolute angle, () → f32 degrees
    println!("{}", chip.angle_raw().unwrap());             // Read scaled angle count, () → u16 0-4095
    println!("{}", chip.raw_angle().unwrap());             // Read raw unscaled angle, () → u16 0-4095
    println!("{}", chip.raw_angle_degrees().unwrap());     // Read raw angle in degrees, () → f32 degrees

    // --- Diagnostics ---
    println!("{}", chip.agc().unwrap());                   // Read AGC value, () → u8
    println!("{}", chip.magnitude().unwrap());             // Read CORDIC magnitude, () → u16

    // --- Position configuration (volatile) ---
    println!("{}", chip.zero_position().unwrap());         // Read ZPOS, () → u16 0-4095
    println!("{}", chip.max_position().unwrap());          // Read MPOS, () → u16 0-4095
    println!("{}", chip.max_angle().unwrap());             // Read MANG, () → u16 0-4095

    chip.set_zero_position(0).unwrap();                    // Set zero position, (pos 0-4095) → None
    chip.set_max_position(4095).unwrap();                  // Set max position, (pos 0-4095) → None
    chip.set_max_angle(2048).unwrap();                     // Set max angle span, (span 0-4095) → None

    // --- Configure power mode and output ---
    chip.configure(0, 0, 0, 0, 0, 0, false).unwrap();     // Configure chip, (pm 0-3, hyst 0-3, outs 0-2, pwmf 0-3, sf 0-3, fth 0-7, wd bool) → None

    // --- Burn count ---
    println!("{}", chip.burn_count().unwrap());            // Read burn count, () → u8 0-3
    loop {}
}
