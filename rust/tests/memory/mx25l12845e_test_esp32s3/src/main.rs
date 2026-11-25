#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::memory::{Mx25l12845eFull, Mx25l12845eOptions};
use periph::storage::BlockDevice;

esp_app_desc!();

// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const SCRATCH: u32 = 0xFF0000;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn now_ms() -> u32 {
    esp_hal::time::Instant::now().duration_since_epoch().as_millis() as u32
}

fn delay_ms(ms: u32) {
    Delay::new().delay_millis(ms);
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    // Wiring: MOSI GPIO3, MISO GPIO4, SCK GPIO5, CS GPIO6; 3.3 V, WP# tied high.
    let spi_bus = Spi::new(peripherals.SPI2, Config::default().with_frequency(Rate::from_mhz(8)))
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let (mut passed, mut failed) = (0i32, 0i32);
    let opt = Mx25l12845eOptions { now_ms: Some(now_ms), delay_ms: Some(delay_ms), auto_erase: false };
    let mut flash = match Mx25l12845eFull::new(device, opt) {                        // Create MX25L12845E driver and check the ID, (spi, options) → Result
        Ok(f) => f,
        Err(_) => {
            check_true!(false, "init (JEDEC C2 20 18)", passed, failed);
            println!("===DONE: {} passed, {} failed===", passed, failed);
            loop {}
        }
    };
    check_true!(true, "init (JEDEC C2 20 18)", passed, failed);
    check_true!(flash.jedec_id().unwrap() == [0xC2, 0x20, 0x18], "jedec_id", passed, failed);
    check_true!(flash.electronic_id().unwrap() == 0x17, "electronic_id (RES) = 17h", passed, failed);
    check_true!(flash.device_ids().unwrap() == (0xC2, 0x17), "device_ids (REMS) = C2 17", passed, failed);
    let sr = flash.read_status().unwrap();
    check_true!(sr & 0x03 == 0, "status: not busy, WEL clear", passed, failed);
    let bp = flash.protection().unwrap();
    println!("INFO status 0x{:02X}, BP={}", sr, bp);
    let mut sfdp = [0u8; 4];
    flash.read_sfdp(0, &mut sfdp).unwrap();
    check_true!(&sfdp == b"SFDP", "SFDP signature", passed, failed);
    if bp != 0 { flash.set_protection(0).unwrap(); }

    let mut back = [0u8; 1024];
    flash.erase_sector(SCRATCH).unwrap();
    flash.read_bytes(SCRATCH, &mut back).unwrap();
    check_true!(back.iter().all(|&b| b == 0xFF), "sector blank (all FF) after erase", passed, failed);

    let mut page = [0u8; 256];
    for (i, b) in page.iter_mut().enumerate() { *b = (i * 3 + 1) as u8; }
    flash.page_program(SCRATCH, &page).unwrap();
    flash.read_bytes(SCRATCH, &mut back[..256]).unwrap();
    check_true!(back[..256] == page[..], "read_bytes matches", passed, failed);
    flash.fast_read(SCRATCH, &mut back[..256]).unwrap();
    check_true!(back[..256] == page[..], "fast_read matches", passed, failed);
    check_true!(flash.page_program(SCRATCH + 250, &page[..10]).is_err(), "page-crossing program refused", passed, failed);

    let mut data = [0u8; 1000];
    for (i, b) in data.iter_mut().enumerate() { *b = (i ^ (i >> 7)) as u8; }
    flash.write_bytes(SCRATCH + 4096 - 100, &data).unwrap();
    let mut got = [0u8; 1000];
    flash.read_bytes(SCRATCH + 4096 - 100, &mut got).unwrap();
    check_true!(got == data, "write_bytes across two sectors + read back", passed, failed);

    flash.erase_block_32k(SCRATCH).unwrap();
    flash.erase_block_64k(SCRATCH).unwrap();
    flash.read_bytes(SCRATCH + 0xF000, &mut back[..16]).unwrap();
    check_true!(back[..16].iter().all(|&b| b == 0xFF), "64 KiB block blank", passed, failed);

    let blk = SCRATCH / 512;
    let mut w = [0u8; 1024];
    for (i, b) in w.iter_mut().enumerate() { *b = (i * 5 + 2) as u8; }
    flash.write(blk, &w).unwrap();
    flash.read(blk, &mut back).unwrap();
    check_true!(back == w, "BlockDevice read back", passed, failed);
    check_true!(flash.write(blk, &[0xFF; 512]).is_err(), "0->1 without erase -> NotErased", passed, failed);
    flash.erase(blk, 8).unwrap();

    flash.set_protection(1).unwrap();
    check_true!(flash.page_program(SCRATCH + 0x8000, &[0]).is_err(), "program into protected area refused", passed, failed);
    flash.set_protection(0).unwrap();

    flash.deep_power_down().unwrap();
    flash.release_deep_power_down().unwrap();
    check_true!(flash.electronic_id().unwrap() == 0x17, "release_deep_power_down, RES again", passed, failed);

    flash.erase_block_64k(SCRATCH).unwrap();
    println!("===DONE: {} passed, {} failed===", passed, failed);

    let mut delay = Delay::new();
    delay.delay_millis(250);
    loop {}
}
