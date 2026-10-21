//! MX25L12845E NOR flash minimal example (Linux host, SPI via spidev).
//!
//! Wiring: MX25L12845E on SPI0 CE0 (SPI_BUS / SPI_DEVICE to change), 3.3 V, WP# tied high.
use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::memory::{Mx25l12845eMinimal, Mx25l12845eOptions};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const SCRATCH: u32 = 0xFF0000;

// Dummy CS: spidev manages CS# in the kernel.
struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn now_ms() -> u32 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u32
}

fn delay_ms(ms: u32) {
    std::thread::sleep(Duration::from_millis(ms as u64));
}

fn options() -> Mx25l12845eOptions {
    Mx25l12845eOptions { now_ms: Some(now_ms), delay_ms: Some(delay_ms), auto_erase: false }
}

fn open_flash() -> ExclusiveDevice<SpidevBus, NullCs, linux_embedded_hal::Delay> {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", bus, dev)).expect("open spi");
    spi.configure(&SpidevOptions::new().max_speed_hz(8_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).expect("configure spi");
    ExclusiveDevice::new(SpidevBus(spi), NullCs, linux_embedded_hal::Delay).expect("spi device")   // Create SPI device, (bus, cs, delay) → ExclusiveDevice
}

fn main() {
    let mut flash = Mx25l12845eMinimal::new(open_flash(), options()).expect("init MX25L12845E");   // Create MX25L12845E driver and check the ID, (spi, options) → Result
    let id = flash.jedec_id().unwrap();                                              // Read JEDEC ID, () → Result<[u8; 3]>
    println!("JEDEC ID {:02X?}, {} MiB", id, flash.size() / (1024 * 1024));          // Capacity in bytes, () → u32

    for counter in 0u8..10 {
        flash.write_bytes(SCRATCH, &[counter]).unwrap();                             // Write bytes (erases the sector when needed), (address, data) → Result
        let mut value = [0u8];
        flash.read_bytes(SCRATCH, &mut value).unwrap();                              // Read bytes, (address, buf) → Result
        println!("wrote 0x{:02X}, read 0x{:02X}", counter, value[0]);
        std::thread::sleep(Duration::from_millis(500));
    }
    flash.erase_sector(SCRATCH).unwrap();                                            // Erase one 4 KiB sector, (address) → Result
    println!("done, scratch sector erased");
}
