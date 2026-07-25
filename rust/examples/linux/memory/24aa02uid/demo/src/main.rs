use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::memory::Eeprom24Aa02UidFull;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Eeprom24Aa02UidFull::new(dev, addr);                                // Create 24AA02UID driver, (i2c, addr=0x50) → Self

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
        sleep(Duration::from_secs(2));
    }
}
