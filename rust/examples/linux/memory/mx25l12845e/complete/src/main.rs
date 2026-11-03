//! MX25L12845E NOR flash complete example (Linux host, SPI via spidev).
//!
//! Wiring: MX25L12845E on SPI0 CE0 (SPI_BUS / SPI_DEVICE to change), 3.3 V, WP# tied high.
use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::memory::{Mx25l12845eFull, Mx25l12845eOptions};
use periph::storage::BlockDevice;
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
    let mut flash = Mx25l12845eFull::new(open_flash(), options()).expect("init MX25L12845E");   // Create MX25L12845E driver and check the ID, (spi, options) → Result

    println!("JEDEC {:02X?}, RES {:02X}, REMS {:02X?}", flash.jedec_id().unwrap(), flash.electronic_id().unwrap(), flash.device_ids().unwrap());   // JEDEC ID, () → Result<[u8; 3]>; RES, () → Result<u8>; REMS, () → Result<(u8, u8)>
    println!("status 0x{:02X}, BP={}, security 0x{:02X}", flash.read_status().unwrap(), flash.protection().unwrap(), flash.read_security().unwrap());   // Status register, () → Result<u8>; BP3:0, () → Result<u8>; security register, () → Result<u8>
    println!("ESN: {:02X?}", flash.serial_number().unwrap());                       // Read the serial-number OTP area, () → Result<[u8; 16]>
                                                                                     // the factory ESN when security bit 0 is set, blank (FF) otherwise
    let mut sfdp = [0u8; 16];
    flash.read_sfdp(0, &mut sfdp).unwrap();                                          // Read the SFDP table, (address, buf) → Result
                                                                                     // starts with the signature "SFDP"
    println!("SFDP: {:02X?}", sfdp);

    flash.erase_sector(SCRATCH).unwrap();                                            // Erase one 4 KiB sector, (address) → Result
                                                                                     // takes 60 ms typ, 300 ms max
    let page: Vec<u8> = (0..=255u8).collect();
    flash.page_program(SCRATCH, &page).unwrap();                                     // Program one page (must not cross a page boundary), (address, data) → Result
                                                                                     // 1.4 ms typ for 256 bytes; bits only go 1 -> 0
    let mut back = [0u8; 256];
    flash.fast_read(SCRATCH, &mut back).unwrap();                                    // FAST_READ, (address, buf) → Result
                                                                                     // same data as read_bytes, for clocks above 50 MHz
    println!("page program + fast_read: {}", if back[..] == page[..] { "ok" } else { "MISMATCH" });

    flash.erase_block_32k(SCRATCH).unwrap();                                         // Erase a 32 KiB block, (address) → Result
                                                                                     // 0.5 s typ
    flash.erase_block_64k(SCRATCH).unwrap();                                         // Erase a 64 KiB block, (address) → Result
                                                                                     // 0.7 s typ
    let mut blank = [0u8; 16];
    flash.read_bytes(SCRATCH, &mut blank).unwrap();                                  // Read bytes, (address, buf) → Result
    println!("after block erase: {:02X?}", blank);
    // chip_erase() (80 s typ, wipes all 16 MiB) is deliberately not called here.

    let text = b"erase-aware write";
    flash.write_bytes(SCRATCH + 100, text).unwrap();                                 // Write bytes, erasing the sector only when needed, (address, data) → Result
    flash.write_bytes(SCRATCH + 100, text).unwrap();                                 // the same bytes again: nothing is programmed or erased
    let mut t = [0u8; 17];
    flash.read_bytes(SCRATCH + 100, &mut t).unwrap();
    println!("text: {}", String::from_utf8_lossy(&t));

    flash.deep_power_down().unwrap();                                                // Enter deep power-down, () → Result
                                                                                     // only RDP / RES are accepted afterwards
    flash.release_deep_power_down().unwrap();                                        // Leave deep power-down (RDP, waits tRES2), () → Result
    println!("busy: {}", flash.is_busy().unwrap());                                  // WIP bit, () → Result<bool>
    flash.wait_ready(1000).unwrap();                                                 // Wait for WIP = 0, (timeout_ms) → Result

    let info = flash.info();                                                         // Geometry and capabilities, () → BlockInfo
                                                                                     // 512-byte blocks, 4 KiB erase unit, needs_erase
    println!("block device: {} blocks of {} bytes, erase unit {}, needs_erase={}", info.block_count, info.block_size, info.erase_size, info.needs_erase);
    // set_protection(), write_status(), program_otp() and lock_otp() change non-volatile state; see the spec before using them.

    flash.erase_sector(SCRATCH).unwrap();
    println!("done");
}
