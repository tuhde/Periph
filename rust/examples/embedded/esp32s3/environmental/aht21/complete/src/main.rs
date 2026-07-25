#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::Aht21Full;

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Aht21Full::new(i2c, ADDR, &mut delay).expect("init AHT21");      // Create AHT21 driver, (i2c, ADDR=0x38, delay) → Result

    println!("is_calibrated: {}", chip.is_calibrated().unwrap());                   // Check calibration status, () → Result<bool>
                                                                                    // reads CAL bit from status byte
    println!("is_busy: {}", chip.is_busy().unwrap());                               // Check busy status, () → Result<bool>
                                                                                    // reads BUSY bit from status byte

    let (t, h) = chip.read(&mut delay).unwrap();                                    // Trigger measurement, (delay) → Result<(f32 °C, f32 %RH)>
                                                                                    // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
    println!("temperature: {:.2} C", t);
    println!("humidity: {:.2} %RH", h);

    println!("read_temperature: {:.2} C", chip.read_temperature(&mut delay).unwrap()); // Read temperature only, (delay) → Result<f32 °C>
                                                                                       // triggers full measurement, returns temperature_c
    println!("read_humidity: {:.2} %RH", chip.read_humidity(&mut delay).unwrap());     // Read humidity only, (delay) → Result<f32 %RH>
                                                                                       // triggers full measurement, returns humidity_pct

    let (tc, hc, crc_ok) = chip.read_with_crc(&mut delay).unwrap();                 // Read with CRC verification, (delay) → Result<(f32 °C, f32 %RH, bool)>
                                                                                    // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    println!("T: {:.2} C  H: {:.2} %RH  CRC: {}", tc, hc, crc_ok);

    chip.soft_reset(&mut delay).unwrap();                                           // Send soft reset command, (delay) → Result<()>
                                                                                    // sends 0xBA, waits 20 ms for recovery
    loop {}
}
