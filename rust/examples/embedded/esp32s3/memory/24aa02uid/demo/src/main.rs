#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::memory::Eeprom24Aa02UidFull;

esp_app_desc!();

const ADDR: u8 = 0x50;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Eeprom24Aa02UidFull::new(i2c, ADDR);                                // Create 24AA02UID driver, (i2c, ADDR=0x50) → Self

    // --- Read the chip's factory-programmed 32-bit serial number ---
    // The UID at 0xFC-0xFF never changes and identifies the device
    // across the entire 256-byte address space.
    let uid = chip.read_uid().expect("read_uid");                                       // Read 32-bit unique serial number, () → Result<[u8; 4]>
                                                                                        // reads 4 bytes at 0xFC-0xFF
    println!("Device UID: {:02X}{:02X}{:02X}{:02X}", uid[0], uid[1], uid[2], uid[3]);
    let uid_int = u32::from_be_bytes(uid);
    println!("Device UID int: {}", uid_int);

    // --- Maintain a 4-byte boot counter in user EEPROM at 0x00-0x03 ---
    // Read the existing value (or zero on a fresh chip), increment,
    // write back as 4 big-endian bytes. The user EEPROM is rewritable;
    // the UID region above 0x80 is not, so the two stay independent.
    let mut existing = [0u8; 4];
    chip.read(0x00, &mut existing).expect("read");                                      // Sequential read, (address, buf) → Result<()>
                                                                                        // reads 4 bytes from user EEPROM
    let mut counter = u32::from_be_bytes(existing);
    counter += 1;
    chip.write(0x00, &counter.to_be_bytes(), &mut delay).expect("write");                // Arbitrary-length write, (address, data, delay) → Result<()>
                                                                                        // writes 4 bytes; waits for each chunk
    println!("Boot count: {}", counter);

    for n in 0..5 {
        // --- Loop reading the UID only, showing it never changes ---
        // The two distinct areas of the chip (immutable identification
        // above 0x80, rewritable storage below 0x80) are exercised
        // independently.
        let uid = chip.read_uid().expect("read_uid");                                   // Read 32-bit unique serial number, () → Result<[u8; 4]>
        println!("[{}] UID: {:02X}{:02X}{:02X}{:02X}  (counter at user EEPROM 0x00-0x03)", n, uid[0], uid[1], uid[2], uid[3]);
        delay.delay_ms(2000);
    }
    loop {}
}
