use std::sync::OnceLock;
use std::time::Instant;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, SpidevBus};
use periph::chips::memory::{SdSpiFull, SdSpiOptions};
use periph::connection::spi::SpiManualCs;
use periph::storage::BlockDevice;
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

/// Wiring: SPI0 (MOSI GPIO10, MISO GPIO9, SCK GPIO11); CS is a free GPIO (default 25) because the
/// kernel cannot hold chip select across calls. Override with SPI_BUS, SPI_DEVICE, GPIOCHIP, CS_LINE.
fn env_or<T: std::str::FromStr>(key: &str, default: T) -> T {
    std::env::var(key).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

fn set_speed(bus: &mut SpidevBus, hz: u32) -> bool {
    bus.0.configure(&SpidevOptions::new().max_speed_hz(hz).mode(SpiModeFlags::SPI_MODE_0 | SpiModeFlags::SPI_NO_CS).build()).is_ok()
}

fn now_ms() -> u32 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u32
}

fn open_card() -> SpiManualCs<SpidevBus, CdevPin> {
    let bus: u8 = env_or("SPI_BUS", 0);
    let dev: u8 = env_or("SPI_DEVICE", 0);
    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", bus, dev)).expect("open spidev");
    spi.configure(&SpidevOptions::new().max_speed_hz(400_000).mode(SpiModeFlags::SPI_MODE_0 | SpiModeFlags::SPI_NO_CS).build()).unwrap(); // SD init needs <= 400 kHz
    let mut chip = Chip::new(env_or("GPIOCHIP", "/dev/gpiochip0".to_string())).expect("open gpio chip");
    let cs = CdevPin::new(chip.get_line(env_or("CS_LINE", 25u32)).unwrap().request(LineRequestFlags::OUTPUT, 1, "sd_spi").unwrap()).unwrap(); // Create CS pin, (line=25) → CdevPin
    SpiManualCs::new(SpidevBus(spi), cs).unwrap().with_speed_setter(set_speed)  // Create SPI connection with GPIO chip select, (bus, cs) → SpiManualCs
}

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let spi = open_card();
    let mut passed = 0i32;
    let mut failed = 0i32;

    let opt = SdSpiOptions { now_ms: Some(now_ms), ..SdSpiFull::<SpiManualCs<SpidevBus, CdevPin>>::defaults() };
    let mut sd = SdSpiFull::new(spi, opt).expect("init SD card");                  // Create SD driver and init the card, (spi, options) → SdSpiFull
    check_true!(true, "init", passed, failed);
    let info = sd.info();
    println!("type={:?} blocks={}", sd.card_type(), info.block_count);
    check_true!(info.block_count > 0 && info.block_size == 512, "geometry", passed, failed);
    check_true!(sd.erase_size() == 512 && !sd.needs_erase(), "no_erase_needed", passed, failed);
    check_true!(sd.is_present().unwrap(), "is_present", passed, failed);
    check_true!(sd.cid().is_ok(), "cid_read", passed, failed);
    check_true!(sd.ocr().unwrap() & 0x00FF_8000 != 0, "ocr_has_3v3_window", passed, failed);

    // Scratch area: the last 8 blocks, contents saved and restored.
    let scratch = sd.block_count() - 8;
    let mut saved = vec![0u8; 8 * 512];
    check_true!(sd.read(scratch, &mut saved).is_ok(), "save_scratch", passed, failed);
    let pat: Vec<u8> = (0..8 * 512usize).map(|i| (i * 7 + 3) as u8).collect();
    let mut back = vec![0u8; 8 * 512];

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
}
