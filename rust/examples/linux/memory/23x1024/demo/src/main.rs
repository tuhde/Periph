use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::memory::{Sram23x1024Full, SRAM23X1024_MODE_PAGE, SRAM23X1024_MODE_SEQUENTIAL};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn main() {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev_path = format!("/dev/spidev{}.{}", bus, dev);

    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(8_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut sram = Sram23x1024Full::new(device).expect("init 23X1024");      // Create 23X1024 Full driver, (spi) → Result

    // --- Store a 256-entry lookup table in external SRAM ---
    // A sine table is the kind of data a microcontroller with little RAM
    // keeps outside: written once, then read back entry by entry.
    let table: Vec<u8> = (0..256)
        .map(|i| (127.5 + 127.5 * (2.0 * std::f32::consts::PI * i as f32 / 256.0).sin()) as u8)
        .collect();
    sram.write(0x00000, &table).expect("write");                              // Block write, (address, data) → Result<()>
    let mut check = vec![0u8; table.len()];
    sram.read(0x00000, &mut check).expect("read");                            // Block read, (address, buf) → Result<()>
    println!("sine table: {} (sin(64/256 turn) = {})",
        if check == table { "ok" } else { "MISMATCH" },
        sram.read_byte(64).expect("read_byte"));                              // Read one byte, (address) → Result<u8>

    // --- Wipe the whole array and spot-check it ---
    // Zero write time means the 128 KiB fill runs at SPI speed with no
    // waiting; the first, middle and last byte prove the full range.
    sram.fill(0x00000, sram.size() as usize, 0xA5).expect("fill");            // Fill a range, (address, length, value) → Result<()>
    println!("fill spot check: 0x{:02X} 0x{:02X} 0x{:02X}",
        sram.read_byte(0x00000).expect("read_byte"),
        sram.read_byte(0x10000).expect("read_byte"),
        sram.read_byte(0x1FFFF).expect("read_byte"));                         // Read one byte, (address) → Result<u8>

    // --- Page mode: a write that crosses a 32-byte page boundary ---
    // The hardware would wrap inside the page; the driver splits the
    // transfer so the 64 bytes starting at address 16 still read back in order.
    sram.set_mode(SRAM23X1024_MODE_PAGE).expect("set_mode");                  // Select operating mode, (mode=MODE_PAGE=0x80) → Result<()>
    let ramp: Vec<u8> = (0..64u32).map(|i| 0x40 + i as u8).collect();
    sram.write(16, &ramp).expect("write");                                    // Block write, (address, data) → Result<()>
    let mut ramp_back = vec![0u8; ramp.len()];
    sram.read(16, &mut ramp_back).expect("read");                             // Block read, (address, buf) → Result<()>
    println!("page-crossing write: {}", if ramp_back == ramp { "ok" } else { "MISMATCH" });
    sram.set_mode(SRAM23X1024_MODE_SEQUENTIAL).expect("set_mode");            // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → Result<()>

    // --- Measure throughput for a 4 KiB transfer ---
    // Sequential mode streams the block in 256-byte transactions;
    // std::time::Instant times them.
    let block: Vec<u8> = (0..4096u32).map(|i| i as u8).collect();
    let t0 = std::time::Instant::now();
    sram.write(0x01000, &block).expect("write");                              // Block write, (address, data) → Result<()>
    let t1 = std::time::Instant::now();
    let mut readback = vec![0u8; block.len()];
    sram.read(0x01000, &mut readback).expect("read");                         // Block read, (address, buf) → Result<()>
    let t2 = std::time::Instant::now();
    println!("4 KiB write: {:.1} ms, read: {:.1} ms", (t1 - t0).as_secs_f64() * 1000.0, (t2 - t1).as_secs_f64() * 1000.0);
}
