#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::memory::{At24c02nFull};

esp_app_desc!();

const ADDR: u8 = 0x50;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create AT24C02N driver, (i2c, ADDR=0x50) → Self
    let mut chip = At24c02nFull::new(i2c, ADDR);

    // --- Persistent boot counter: 4-byte big-endian value at address 0 ---
    // Sequential read, (address, buf) → Result<()>
    let mut cb = [0u8; 4];
    chip.read(0x0000, &mut cb).expect("read counter");
    let counter = u32::from_be_bytes(cb).wrapping_add(1);
    // Write data, (address, data, delay) → Result<()>
    chip.write(0x0000, &counter.to_be_bytes(), &mut delay).expect("write counter");
    println!("Boot counter: {}", counter);

    // --- Write 3 pages, 8-byte aligned, and read them back ---
    // Get write page size, () → usize
    let size = 3 * chip.page_size();
    let mut pattern = [0u8; 24];
    for (i, b) in pattern.iter_mut().enumerate() {
        *b = ((i * 7) & 0xFF) as u8;
    }
    chip.write(0x0080, &pattern, &mut delay).expect("write pattern");
    let mut back = [0u8; 24];
    chip.read(0x0080, &mut back).expect("read pattern");
    println!("Read-back matches: {}", back == pattern);

    // --- A write that crosses a page boundary is refused ---
    // Write within one page, (address, data, delay) → Result<()>
    println!("write_page across boundary: {:?}", chip.write_page(0x00F4, &[0u8; 8], &mut delay));

    // Fill a range, (address, length, value, delay) → Result<()>
    chip.fill(0x0080, size, 0, &mut delay).expect("fill");
    // Get array size, () → usize ; ACK-poll the chip, (timeout_ms, delay) → bool
    println!("Size {}, ready {}", chip.size(), chip.wait_ready(10, &mut delay));

    loop {
        delay.delay_ms(1000);
    }
}
