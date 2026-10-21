#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::Mode;
use esp_hal::time::Rate;
use esp_hal::Blocking;
use esp_println::println;
use periph::chips::memory::{SdSpiFull, SdSpiOptions};
use periph::connection::spi::SpiManualCs;
use periph::storage::BlockDevice;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

type Bus = Spi<'static, Blocking>;

fn set_speed(bus: &mut Bus, hz: u32) -> bool {
    bus.apply_config(&Config::default().with_frequency(Rate::from_hz(hz)).with_mode(Mode::_0)).is_ok()
}

fn now_ms() -> u32 {
    esp_hal::time::Instant::now().duration_since_epoch().as_millis() as u32
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    // Wiring: MOSI GPIO3, MISO GPIO4, SCK GPIO5, CS GPIO6 (driven as a plain GPIO by SpiManualCs).
    let bus = Spi::new(peripherals.SPI2, Config::default().with_frequency(Rate::from_khz(400)).with_mode(Mode::_0)) // SD init needs <= 400 kHz
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());
    let spi = SpiManualCs::new(bus, cs).unwrap().with_speed_setter(set_speed);  // Create SPI connection with GPIO chip select, (bus, cs) → SpiManualCs

    let mut passed = 0i32;
    let mut failed = 0i32;

    let opt = SdSpiOptions { now_ms: Some(now_ms), ..SdSpiFull::<SpiManualCs<Bus, Output<'static>>>::defaults() };
    let mut sd = match SdSpiFull::new(spi, opt) {                                // Create SD driver and init the card, (spi, options) → SdSpiFull
        Ok(sd) => sd,
        Err(_) => {
            check_true!(false, "init", passed, failed);
            println!("===DONE: {} passed, {} failed===", passed, failed);
            loop {}
        }
    };
    check_true!(true, "init", passed, failed);
    let info = sd.info();
    println!("blocks={}", info.block_count);
    check_true!(info.block_count > 0 && info.block_size == 512, "geometry", passed, failed);
    check_true!(sd.erase_size() == 512 && !sd.needs_erase(), "no_erase_needed", passed, failed);
    check_true!(sd.is_present().unwrap_or(false), "is_present", passed, failed);
    check_true!(sd.cid().is_ok(), "cid_read", passed, failed);
    check_true!(sd.ocr().map(|o| o & 0x00FF_8000 != 0).unwrap_or(false), "ocr_has_3v3_window", passed, failed);

    // Scratch area: the last 4 blocks, contents saved and restored.
    let scratch = sd.block_count() - 4;
    let mut saved = [0u8; 4 * 512];
    let mut pat = [0u8; 4 * 512];
    let mut back = [0u8; 4 * 512];
    check_true!(sd.read(scratch, &mut saved).is_ok(), "save_scratch", passed, failed);
    for (i, b) in pat.iter_mut().enumerate() { *b = (i * 7 + 3) as u8; }

    check_true!(sd.write(scratch, &pat[..512]).is_ok(), "write_single", passed, failed);
    check_true!(sd.read(scratch, &mut back[..512]).is_ok() && back[..512] == pat[..512], "single_roundtrip", passed, failed);
    check_true!(sd.write(scratch, &pat).is_ok(), "write_multi", passed, failed);
    check_true!(sd.sync().is_ok(), "sync", passed, failed);
    check_true!(sd.read(scratch, &mut back).is_ok() && back == pat, "multi_roundtrip", passed, failed);
    check_true!(sd.read(sd.block_count(), &mut back[..512]).is_err(), "out_of_range", passed, failed);

    check_true!(sd.set_crc(false).is_ok(), "crc_off", passed, failed);
    check_true!(sd.read(scratch, &mut back[..512]).is_ok() && back[..512] == pat[..512], "read_with_crc_off", passed, failed);
    check_true!(sd.set_crc(true).is_ok(), "crc_on", passed, failed);
    check_true!(sd.set_speed(8_000_000), "set_speed_8mhz", passed, failed);
    check_true!(sd.read(scratch, &mut back).is_ok() && back == pat, "read_at_higher_speed", passed, failed);

    check_true!(sd.write(scratch, &saved).is_ok(), "restore_scratch", passed, failed);
    check_true!(sd.sync().is_ok(), "final_sync", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    let mut delay = Delay::new();
    delay.delay_millis(250);
    loop {}
}
