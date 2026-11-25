#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::memory::{Cat24c512Full};

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

    // Create CAT24C512 driver, (i2c, ADDR=0x50) → Self
    let mut chip = Cat24c512Full::new(i2c, ADDR);

    // Get array size, () → usize
    // always 65536
    println!("Size: {} bytes", chip.size());
    // Get write page size, () → usize
    // always 128
    println!("Page: {} bytes", chip.page_size());
    // Set max bytes per bus transaction, (max_chunk=30) → ()
    // clamped to 1-32
    chip.set_max_chunk(16);
    println!("max_chunk: {}", chip.max_chunk());

    // Write a single byte, (address 0–65535, value 0–255, delay) → Result<()>
    // ACK-polled until the write cycle ends
    chip.write_byte(0x0010, 0xA5, &mut delay).expect("write_byte");
    // Read a single byte, (address 0–65535) → Result<u8>
    // random read
    println!("Byte: 0x{:02X}", chip.read_byte(0x0010).expect("read_byte"));

    // Write data, (address, data, delay) → Result<()>
    // split at 128-byte page boundaries and max_chunk, ACK-polled per chunk
    let mut data = [0u8; 40];
    for (i, b) in data.iter_mut().enumerate() {
        *b = i as u8;
    }
    chip.write(0x0100, &data, &mut delay).expect("write");
    // Sequential read, (address, buf) → Result<()>
    // split into max_chunk-byte transactions
    let mut back = [0u8; 40];
    chip.read(0x0100, &mut back).expect("read");
    println!("Block: {:02X?}", &back[..8]);

    // Write within one page, (address, data, delay) → Result<()>
    // Err(PageBoundary) if the data crosses a page boundary
    chip.write_page(0x0180, &data[..16], &mut delay).expect("write_page");
    // Fill a range, (address, length, value, delay) → Result<()>
    // page-wise writes of value
    chip.fill(0x0100, 40, 0xFF, &mut delay).expect("fill");

    // ACK-poll the chip, (timeout_ms, delay) → bool
    // true once the chip answers its address
    println!("Ready: {}", chip.wait_ready(10, &mut delay));

    loop {
        delay.delay_ms(1000);
    }
}
