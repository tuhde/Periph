use linux_embedded_hal::I2cdev;
use periph::chips::memory::Mb85rcFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Mb85rcFull::new(dev, addr);                                                               // Create MB85RC driver, (i2c, addr=0x50) → Self

    let size = chip.size();                                                                              // Get array size, () → usize
                                                                                          // always 32768 for the MB85RC256V
    println!("Size: {} bytes", size);

    chip.set_max_chunk(16);                                                                   // Set max bytes per bus transaction, (max_chunk=30) → ()
    println!("max_chunk: {}", chip.max_chunk());                  // Get max bytes per bus transaction, () → usize

    chip.write_byte(0x0010, 0xA5).expect("write_byte");                                       // Write a single byte, (address 0–32767, value 0–255) → Result<()>
                                                                                          // no write cycle, no polling
    let b = chip.read_byte(0x0010).expect("read_byte");                                               // Read a single byte, (address 0–32767) → Result<u8>
                                                                                          // random read
    println!("Byte: 0x{:02X}", b);

    let data: Vec<u8> = (0..40).collect();
    chip.write(0x0100, &data).expect("write");                                                // Sequential write, (address, data) → Result<()>
                                                                                          // split into max_chunk-byte transactions
    let mut back = [0u8; 40];
    chip.read(0x0100, &mut back).expect("read");                                              // Sequential read, (address, buf) → Result<()>
                                                                                          // split into max_chunk-byte transactions
    println!("Block: {:02X?}", &back[..8]);

    chip.fill(0x0100, 40, 0xFF).expect("fill");                                               // Fill a range, (address, length, value) → Result<()>
                                                                                          // writes value over the range in chunks

    match chip.read_device_id() {                                                                   // Read Device ID, () → Result<(u16, u8, u8)>
                                                                                          // reads 3 bytes via reserved address 0x7C
        Ok((m, d, p)) => println!("Device ID: mfr=0x{:03X} density=0x{:X} product=0x{:02X}", m, d, p),
        Err(_) => println!("Device ID not readable on this bus"),
    }
}
