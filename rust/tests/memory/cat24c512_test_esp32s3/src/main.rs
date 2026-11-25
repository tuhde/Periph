//! CAT24C512 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; A2:A0 low (address 0x50).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::memory::Cat24c512Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(400)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = Cat24c512Full::new(i2c, 0x50);

    esp_println::println!("CAT24C512 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    check_true!(chip.size() == 65536, "size");
    check_true!(chip.page_size() == 128, "page_size");

    chip.write_byte(0x0010, 0x5A, &mut delay).expect("write_byte");
    check_true!(chip.read_byte(0x0010).expect("read_byte") == 0x5A, "write_byte/read_byte round-trip");
    check_true!(chip.wait_ready(10, &mut delay), "wait_ready after write");

    let mut data = [0u8; 200];
    for (i, b) in data.iter_mut().enumerate() {
        *b = i as u8;
    }
    chip.write(0x0070, &data, &mut delay).expect("write");
    let mut back = [0u8; 200];
    chip.read(0x0070, &mut back).expect("read");
    check_true!(back == data, "page-split write/read round-trip");

    let mut page = [0u8; 128];
    for (i, b) in page.iter_mut().enumerate() {
        *b = i as u8;
    }
    chip.write_page(0x0300, &page, &mut delay).expect("write_page");
    let mut pb = [0u8; 128];
    chip.read(0x0300, &mut pb).expect("read page");
    check_true!(pb == page, "write_page round-trip");
    check_true!(chip.write_page(0x037C, &[0u8; 8], &mut delay).is_err(), "write_page crossing rejected");

    chip.fill(0x0100, 100, 0xC3, &mut delay).expect("fill");
    let mut f = [0u8; 100];
    chip.read(0x0100, &mut f).expect("read fill");
    check_true!(f.iter().all(|b| *b == 0xC3), "fill");
    chip.fill(0x0100, 100, 0, &mut delay).expect("fill zero");
    chip.read(0x0100, &mut f).expect("read fill");
    check_true!(f.iter().all(|b| *b == 0), "fill zero");

    chip.write(0xFFFE, &[0x12, 0x34], &mut delay).expect("write tail");
    let mut tail = [0u8; 2];
    chip.read(0xFFFE, &mut tail).expect("read tail");
    check_true!(tail == [0x12, 0x34], "last bytes round-trip");
    check_true!(chip.read(0xFFFF, &mut tail).is_err(), "wrap rejected");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
