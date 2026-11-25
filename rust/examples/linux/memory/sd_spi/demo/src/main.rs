//! SD card (SPI mode) demo example (Linux host, e.g. Raspberry Pi).
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

const BLOCKS: u32 = 64;

fn main() {
    // --- Bring up the card ---
    // The card starts in SD mode; the driver sends 80 clocks with CS high, CMD0 with CS
    // low to enter SPI mode, then negotiates the card type. SCK runs at 400 kHz during
    // init and is raised to 12 MHz afterwards.
    let spi = open_card();
    let opt = SdSpiOptions { now_ms: Some(now_ms), ..SdSpiFull::<SpiManualCs<SpidevBus, CdevPin>>::defaults() }; // Options with CRC16 checking on, () → SdSpiOptions
    let mut sd = SdSpiFull::new(spi, opt).expect("init SD card");                   // Create SD driver and init the card, (spi, options) → SdSpiFull
    let info = sd.info();                                                           // Geometry and capabilities, () → BlockInfo
    println!("card {}, {} MiB", String::from_utf8_lossy(&info.name).trim_end_matches('\0'), info.block_count / 2048);

    // --- Log samples to a raw block range (filesystem-free data logger) ---
    // Each 512-byte block holds a header (sequence number, tick) and 126 samples.
    // The last blocks of the card are used and restored afterwards, so a card that
    // carries a filesystem keeps its data (only the tail of the volume is touched).
    let first = sd.block_count() - BLOCKS;
    let mut saved = vec![0u8; (BLOCKS * 512) as usize];
    sd.read(first, &mut saved).unwrap();                                            // Read blocks, (block, buf) → Result
    let t0 = now_ms();
    for seq in 0..BLOCKS {
        let mut block = [0u8; 512];
        let mut words = [0u32; 128];
        words[0] = seq;
        words[1] = now_ms();
        for (i, w) in words.iter_mut().enumerate().skip(2) { *w = seq * 1000 + i as u32; }
        for (i, w) in words.iter().enumerate() { block[i * 4..i * 4 + 4].copy_from_slice(&w.to_le_bytes()); }
        sd.write(first + seq, &block).unwrap();                                     // Write blocks, (block, data) → Result
    }
    sd.sync().unwrap();                                                             // Wait until programming is done, () → Result
    println!("wrote {} blocks in {} ms", BLOCKS, now_ms() - t0);

    // --- Verify by reading back ---
    // Read every block back and recompute the expected sample values; any single
    // mismatch (or a CRC16 error returned by the driver) is counted.
    let mut bad = 0;
    let mut block = [0u8; 512];
    for seq in 0..BLOCKS {
        if sd.read(first + seq, &mut block).is_err() { bad += 1; continue; }        // Read blocks, (block, buf) → Result
        let word = |i: usize| u32::from_le_bytes([block[i * 4], block[i * 4 + 1], block[i * 4 + 2], block[i * 4 + 3]]);
        if word(0) != seq || (2..128).any(|i| word(i) != seq * 1000 + i as u32) { bad += 1; }
    }
    println!("verify: {} bad blocks", bad);

    // --- Restore the original contents ---
    sd.write(first, &saved).unwrap();
    sd.sync().unwrap();
    println!("restored");
}
