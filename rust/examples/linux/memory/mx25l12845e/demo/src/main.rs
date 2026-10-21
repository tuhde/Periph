//! MX25L12845E NOR flash demo example (Linux host, SPI via spidev).
//!
//! Wiring: MX25L12845E on SPI0 CE0 (SPI_BUS / SPI_DEVICE to change), 3.3 V, WP# tied high.
use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::memory::{Mx25l12845eFull, Mx25l12845eOptions};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

// The demo uses the last 16 KiB of the chip (0xFFC000..0xFFFFFF) and leaves it blank.

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

const RING: u32 = 0xFFC000;
const SECTORS: u32 = 4;
const SLOT: u32 = 32;
const SLOTS: u32 = SECTORS * 4096 / SLOT;

fn main() {
    // --- Bring up the chip ---
    // Plain single-I/O SPI: the driver releases deep power-down, checks the JEDEC ID and
    // waits until the chip is idle. The serial-number area is the factory ESN on parts that have one.
    let mut flash = Mx25l12845eFull::new(open_flash(), options()).expect("init MX25L12845E");   // Create MX25L12845E driver and check the ID, (spi, options) → Result
    println!("MX25L12845E, {} MiB, serial {:02X?}", flash.size() / (1024 * 1024), flash.serial_number().unwrap());   // Capacity in bytes, () → u32; serial-number area, () → Result<[u8; 16]>

    // --- Append records to a ring of four sectors ---
    // 32-byte records (sequence, tick, payload) fill a sector, 128 per sector. A blank slot
    // (all 0xFF) needs no erase, so appending is just a page program; only when the writer
    // enters the next sector is that sector erased first — one erase per 128 records.
    for s in 0..SECTORS { flash.erase_sector(RING + s * 4096).unwrap(); }            // Erase one 4 KiB sector, (address) → Result
    let t0 = now_ms();
    let (mut erases, mut slot) = (0, 0u32);
    for seq in 0u32..300 {
        if slot % 128 == 0 && seq >= SLOTS {                                         // wrapped into the next sector: make it blank
            flash.erase_sector(RING + (slot / 128) * 4096).unwrap();
            erases += 1;
        }
        let mut rec = [0xA5u8; SLOT as usize];
        rec[..4].copy_from_slice(&seq.to_le_bytes());
        rec[4..8].copy_from_slice(&now_ms().to_le_bytes());
        flash.page_program(RING + slot * SLOT, &rec).unwrap();                       // Program one record (inside a page), (address, data) → Result
        slot = (slot + 1) % SLOTS;
    }
    println!("appended 300 records in {} ms, {} sector erases", now_ms() - t0, erases);

    // --- Verify by scanning the ring ---
    // Every slot must carry a valid sequence number and the 0xA5 payload.
    let (mut good, mut newest) = (0, 0u32);
    for s in 0..SLOTS {
        let mut rec = [0u8; SLOT as usize];
        flash.read_bytes(RING + s * SLOT, &mut rec).unwrap();                        // Read bytes, (address, buf) → Result
        let seq = u32::from_le_bytes([rec[0], rec[1], rec[2], rec[3]]);
        if seq != 0xFFFF_FFFF && rec[20] == 0xA5 { good += 1; newest = newest.max(seq); }
    }
    println!("ring holds {} valid records, newest sequence number {}", good, newest);

    // --- Leave the chip clean ---
    for s in 0..SECTORS { flash.erase_sector(RING + s * 4096).unwrap(); }
    println!("ring erased");
}
