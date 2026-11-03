//! SD card (SPI mode) minimal example (Linux host, e.g. Raspberry Pi).
//! Wiring: SPI0 (MOSI GPIO10, MISO GPIO9, SCK GPIO11), CS on a free GPIO (default 25, CS_LINE) because the kernel
//! cannot hold chip select across calls. Overrides: SPI_BUS, SPI_DEVICE, GPIOCHIP, CS_LINE. 3.3 V card only.
use std::sync::OnceLock;
use std::time::Instant;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, SpidevBus};
use periph::chips::memory::{SdSpiMinimal, SdSpiOptions};
use periph::connection::spi::SpiManualCs;
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
    let mut sd = SdSpiMinimal::new(spi, SdSpiOptions { now_ms: Some(now_ms), ..Default::default() }).expect("init SD card"); // Create SD driver and init the card, (spi, options) → SdSpiMinimal

    println!("capacity: {} blocks = {} MiB", sd.block_count(), sd.block_count() / 2048);  // Capacity in 512-byte blocks, () → u32
    let mut block = [0u8; 512];
    loop {
        sd.read_block(0, &mut block).unwrap();                                      // Read one 512-byte block, (block, buf) → Result
        println!("block 0: {:02X?}", &block[..16]);
        println!("boot signature: {:02X}{:02X}", block[510], block[511]);
        std::thread::sleep(std::time::Duration::from_secs(2));
    }
}
