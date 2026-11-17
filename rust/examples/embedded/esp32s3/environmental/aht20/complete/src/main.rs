#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::Aht20Full;

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

    let mut chip = Aht20Full::new(i2c, ADDR, &mut delay).expect("init AHT20");      // Create AHT20 driver, (i2c, ADDR=0x38, delay) → Result

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

    let (raw_rh, raw_t) = chip.read_raw(&mut delay).unwrap();                       // Read raw 20-bit values, (delay) → Result<(u32, u32)>
                                                                                    // same measurement as read(), undecoded
    println!("raw RH: {}  raw T: {}", raw_rh, raw_t);
    let (_t, _h, crc_ok) = chip.read_with_crc(&mut delay).unwrap();                  // Read with CRC check, (delay) → Result<(f32, f32, bool)>
                                                                                    // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    println!("CRC ok: {}", crc_ok);
    println!("Status: 0x{:02X}", chip.read_status().unwrap());                      // Read status byte, () → Result<u8>
                                                                                    // bit 7 BUSY, bit 3 CAL
    println!("Dew point: {:.2} C", chip.dew_point(&mut delay).unwrap());            // Dew point, (delay) → Result<f32 °C>
                                                                                    // one fresh measurement, Magnus formula

    chip.soft_reset(&mut delay).unwrap();                                           // Send soft reset command, (delay) → Result<()>
                                                                                    // sends 0xBA, waits 20 ms, re-runs the calibration check
    loop {}
}
