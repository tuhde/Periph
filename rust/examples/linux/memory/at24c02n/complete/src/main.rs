//! Complete example for the AT24C02N — every At24c02nFull method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::memory::{At24c02nFull};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create AT24C02N driver, (i2c, addr=0x50) → Self
    let mut chip = At24c02nFull::new(dev, addr);

    // Get array size, () → usize
    // always 256
    println!("Size: {} bytes", chip.size());
    // Get write page size, () → usize
    // always 8
    println!("Page: {} bytes", chip.page_size());
    // Set max bytes per bus transaction, (max_chunk=30) → ()
    // clamped to 1-8
    chip.set_max_chunk(16);
    println!("max_chunk: {}", chip.max_chunk());

    // Write a single byte, (address 0–255, value 0–255, delay) → Result<()>
    // ACK-polled until the write cycle ends
    chip.write_byte(0x0010, 0xA5, &mut delay).expect("write_byte");
    // Read a single byte, (address 0–255) → Result<u8>
    // random read
    println!("Byte: 0x{:02X}", chip.read_byte(0x0010).expect("read_byte"));

    // Write data, (address, data, delay) → Result<()>
    // split at 8-byte page boundaries and max_chunk, ACK-polled per chunk
    let mut data = [0u8; 40];
    for (i, b) in data.iter_mut().enumerate() {
        *b = i as u8;
    }
    chip.write(0x0040, &data, &mut delay).expect("write");
    // Sequential read, (address, buf) → Result<()>
    // split into max_chunk-byte transactions
    let mut back = [0u8; 40];
    chip.read(0x0040, &mut back).expect("read");
    println!("Block: {:02X?}", &back[..8]);

    // Write within one page, (address, data, delay) → Result<()>
    // Err(PageBoundary) if the data crosses a page boundary
    chip.write_page(0x00B8, &data[..8], &mut delay).expect("write_page");
    // Fill a range, (address, length, value, delay) → Result<()>
    // page-wise writes of value
    chip.fill(0x0040, 40, 0xFF, &mut delay).expect("fill");

    // ACK-poll the chip, (timeout_ms, delay) → bool
    // true once the chip answers its address
    println!("Ready: {}", chip.wait_ready(10, &mut delay));
}
