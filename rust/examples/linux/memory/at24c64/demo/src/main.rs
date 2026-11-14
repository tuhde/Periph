//! Demo for the AT24C64 — persistent boot counter, page-write benchmark and page-boundary rules.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::memory::{At24c64Full};

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

    // Create AT24C64 driver, (i2c, addr=0x50) → Self
    let mut chip = At24c64Full::new(dev, addr);

    // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
    // Sequential read, (address, buf) → Result<()>
    let mut cb = [0u8; 4];
    chip.read(0x0000, &mut cb).expect("read counter");
    let counter = u32::from_be_bytes(cb).wrapping_add(1);
    // Write data, (address, data, delay) → Result<()>
    chip.write(0x0000, &counter.to_be_bytes(), &mut delay).expect("write counter");
    println!("Boot counter: {}", counter);

    // --- Write 3 pages, 32-byte aligned, and read them back ---
    // Get write page size, () → usize
    let size = 3 * chip.page_size();
    let mut pattern = [0u8; 96];
    for (i, b) in pattern.iter_mut().enumerate() {
        *b = ((i * 7) & 0xFF) as u8;
    }
    chip.write(0x0080, &pattern, &mut delay).expect("write pattern");
    let mut back = [0u8; 96];
    chip.read(0x0080, &mut back).expect("read pattern");
    println!("Read-back matches: {}", back == pattern);

    // --- A write that crosses a page boundary is refused ---
    // Write within one page, (address, data, delay) → Result<()>
    println!("write_page across boundary: {:?}", chip.write_page(0x00FC, &[0u8; 8], &mut delay));

    // Fill a range, (address, length, value, delay) → Result<()>
    chip.fill(0x0080, size, 0, &mut delay).expect("fill");
    // Get array size, () → usize ; ACK-poll the chip, (timeout_ms, delay) → bool
    println!("Size {}, ready {}", chip.size(), chip.wait_ready(10, &mut delay));
}
