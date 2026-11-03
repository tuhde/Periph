#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::memory::{Sram23x1024Full, SRAM23X1024_MODE_BYTE, SRAM23X1024_MODE_PAGE, SRAM23X1024_MODE_SEQUENTIAL};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut sram = Sram23x1024Full::new(device).expect("init 23X1024");      // Create 23X1024 Full driver, (spi) → Result
    check_true!(true, "init_mode_roundtrip", passed, failed);                // constructor writes MODE=0x40 and verifies it with RDMR
    check_true!(sram.size() == 131072, "size", passed, failed);
    check_true!(sram.get_mode().unwrap() == SRAM23X1024_MODE_SEQUENTIAL, "default_mode_sequential", passed, failed);

    sram.write_byte(0x00000, 0xA5).unwrap();
    check_true!(sram.read_byte(0x00000).unwrap() == 0xA5, "byte_roundtrip_first", passed, failed);
    sram.write_byte(0x1FFFF, 0x5A).unwrap();
    check_true!(sram.read_byte(0x1FFFF).unwrap() == 0x5A, "byte_roundtrip_last", passed, failed);
    sram.write_byte(0x10000, 0x3C).unwrap();
    check_true!(sram.read_byte(0x10000).unwrap() == 0x3C && sram.read_byte(0x00000).unwrap() == 0xA5, "address_bit16_distinct", passed, failed);

    let pattern: [u8; 1000] = { let mut p = [0u8; 1000]; for (i, b) in p.iter_mut().enumerate() { *b = (i as u32 * 7 + 3) as u8; } p };
    sram.write(0x00400, &pattern).unwrap();
    let mut back = [0u8; 1000];
    sram.read(0x00400, &mut back).unwrap();
    check_true!(back == pattern, "block_roundtrip_chunked", passed, failed);

    sram.fill(0x02000, 100, 0xC3).unwrap();
    let mut filled = [0u8; 100];
    sram.read(0x02000, &mut filled).unwrap();
    check_true!(filled.iter().all(|&b| b == 0xC3) && sram.read_byte(0x02064).unwrap() != 0xC3, "fill_range", passed, failed);

    sram.write(0x1FFF0, &pattern[..16]).unwrap();
    let mut tail = [0u8; 16];
    sram.read(0x1FFF0, &mut tail).unwrap();
    check_true!(tail[..] == pattern[..16], "block_at_array_end", passed, failed);

    sram.set_mode(SRAM23X1024_MODE_PAGE).unwrap();
    check_true!(sram.get_mode().unwrap() == SRAM23X1024_MODE_PAGE, "set_mode_page", passed, failed);
    sram.write(0x04010, &pattern[..64]).unwrap();                            // crosses the pages at 0x4020 and 0x4040
    let mut paged = [0u8; 64];
    sram.read(0x04010, &mut paged).unwrap();
    check_true!(paged[..] == pattern[..64], "page_mode_crossing_linear", passed, failed);

    sram.set_mode(SRAM23X1024_MODE_BYTE).unwrap();
    check_true!(sram.get_mode().unwrap() == SRAM23X1024_MODE_BYTE, "set_mode_byte", passed, failed);
    sram.write(0x05000, &pattern[..8]).unwrap();
    let mut bytes8 = [0u8; 8];
    sram.read(0x05000, &mut bytes8).unwrap();
    check_true!(bytes8[..] == pattern[..8], "byte_mode_block", passed, failed);

    sram.set_mode(SRAM23X1024_MODE_SEQUENTIAL).unwrap();
    check_true!(sram.get_mode().unwrap() == SRAM23X1024_MODE_SEQUENTIAL, "set_mode_sequential", passed, failed);

    sram.reset_io().unwrap();
    check_true!(sram.get_mode().unwrap() == SRAM23X1024_MODE_SEQUENTIAL, "reset_io_stays_spi", passed, failed);
    check_true!(sram.read_byte(0x00000).unwrap() == 0xA5, "data_survives_reset_io", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    let mut delay = Delay::new();
    delay.delay_millis(250);
    loop {}
}
