//! SD card (SPI mode) complete example (Linux host, e.g. Raspberry Pi).
//! Wiring: SPI0 (MOSI GPIO10, MISO GPIO9, SCK GPIO11), CS on a free GPIO (default 25, CS_LINE) because the kernel
//! cannot hold chip select across calls. Overrides: SPI_BUS, SPI_DEVICE, GPIOCHIP, CS_LINE. 3.3 V card only.
use std::sync::OnceLock;
use std::time::Instant;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, SpidevBus};
use periph::chips::memory::{SdSpiFull, SdSpiOptions};
use periph::connection::spi::SpiManualCs;
use periph::storage::BlockDevice;
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

// Wiring: SPI0 (MOSI GPIO10, MISO GPIO9, SCK GPIO11); CS is a free GPIO (default 25) because the
// kernel cannot hold chip select across calls. Override with SPI_BUS, SPI_DEVICE, GPIOCHIP, CS_LINE.
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

fn main() {
    let spi = open_card();
    let opt = SdSpiOptions { now_ms: Some(now_ms), ..SdSpiFull::<SpiManualCs<SpidevBus, CdevPin>>::defaults() }; // Options with CRC16 checking on, () → SdSpiOptions
    let mut sd = SdSpiFull::new(spi, opt).expect("init SD card");                   // Create SD driver and init the card, (spi, options) → SdSpiFull

    println!("type: {:?}", sd.card_type());                                         // Card generation, () → SdCardType
    let info = sd.info();                                                           // Geometry and capabilities, () → BlockInfo
                                                                                    // block size, count, erase unit, name from the CID
    println!("{:?}", info);
    println!("CID: {:02X?}", sd.cid().unwrap());                                    // Read CID register, () → Result<[u8; 16]>
                                                                                    // manufacturer, name, revision, serial, date
    println!("CSD: {:02X?}", sd.csd());                                             // CSD register read at init, () → [u8; 16]
    println!("OCR: {:08X}", sd.ocr().unwrap());                                     // Read OCR, () → Result<u32>
                                                                                    // voltage window and CCS (block addressing) bit
    println!("present: {}", sd.is_present().unwrap());                              // Card responds, () → Result<bool>

    // Use the last 8 blocks of the card as scratch, restoring their contents.
    let scratch = sd.block_count() - 8;
    let mut saved = vec![0u8; 8 * 512];
    sd.read(scratch, &mut saved).unwrap();                                          // Read blocks, (block, buf) → Result
                                                                                    // CMD18 multi-block read for more than one block
    let pattern: Vec<u8> = (0..8 * 512usize).map(|i| (i * 7 + 3) as u8).collect();
    sd.write(scratch, &pattern).unwrap();                                           // Write blocks, (block, data) → Result
                                                                                    // CMD25 multi-block write with stop token
    sd.sync().unwrap();                                                             // Wait until programming is done, () → Result
    let mut back = vec![0u8; 8 * 512];
    sd.read(scratch, &mut back).unwrap();
    println!("roundtrip: {}", if back == pattern { "ok" } else { "MISMATCH" });

    sd.set_crc(false).unwrap();                                                     // Disable CRC checking, (enabled) → Result
                                                                                    // CMD59 off; data CRC16 is no longer verified
    sd.set_crc(true).unwrap();
    println!("set_speed: {}", sd.set_speed(6_000_000));                             // Change SCK frequency, (hz) → bool
                                                                                    // reconfigures spidev's max_speed_hz
    sd.erase(scratch, 8).unwrap();                                                  // Erase blocks, (block, count) → Result
                                                                                    // CMD32/33/38; contents afterwards are card dependent
    sd.write(scratch, &saved).unwrap();                                             // restore the original contents
    println!("done");
}
