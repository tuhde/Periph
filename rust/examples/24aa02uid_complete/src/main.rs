use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::memory::Eeprom24Aa02UidFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Eeprom24Aa02UidFull::new(dev, addr);                                // Create 24AA02UID driver, (i2c, addr=0x50) → Self

    let uid = chip.read_uid().expect("read_uid");                                       // Read 32-bit unique serial number, () → Result<[u8; 4]>
                                                                                        // reads 4 bytes at 0xFC-0xFF
    println!("UID bytes: {:02X}{:02X}{:02X}{:02X}", uid[0], uid[1], uid[2], uid[3]);
    let uid_int = u32::from_be_bytes(uid);
    println!("UID int:   {}", uid_int);

    let mfr = chip.read_manufacturer_code().expect("read_manufacturer_code");           // Read manufacturer code, () → Result<u8>
                                                                                        // reads 0xFA; expect 0x29 (Microchip)
    let dev_code = chip.read_device_code().expect("read_device_code");                   // Read device code, () → Result<u8>
                                                                                        // reads 0xFB; expect 0x41
    println!("MFR: 0x{:02X}  DEV: 0x{:02X}", mfr, dev_code);

    let first = chip.read_byte(0x00).expect("read_byte");                               // Read a single byte, (address=0x00-0x7F) → Result<u8>
                                                                                        // random read at user EEPROM address
    println!("First byte: 0x{:02X}", first);

    chip.write_byte(0x10, 0xA5, &mut delay).expect("write_byte");                        // Write a single byte, (address, value, delay) → Result<()>
                                                                                        // byte write + delay until complete (max 5 ms)
    let verify = chip.read_byte(0x10).expect("read_byte");                               // Read a single byte, (address=0x00-0x7F) → Result<u8>
    println!("Wrote 0xA5, read back: 0x{:02X}", verify);

    let mut block = [0u8; 8];
    chip.read(0x20, &mut block).expect("read");                                          // Sequential read, (address, buf) → Result<()>
                                                                                        // reads 8 bytes starting at address
    print!("Block @ 0x20:");
    for b in block.iter() { print!(" {:02X}", b); }
    println!();

    chip.write_page(0x40, &[0x01, 0x02, 0x03, 0x04], &mut delay).expect("write_page");  // Page write, (address, data, delay) → Result<()>
                                                                                        // writes up to 8 bytes within one page
    chip.write(0x44, &[0xAA, 0xBB, 0xCC, 0xDD, 0xEE], &mut delay).expect("write");       // Arbitrary-length write, (address, data, delay) → Result<()>
                                                                                        // splits at 8-byte page boundaries; waits for each chunk
    println!("Multi-page write complete");
}
