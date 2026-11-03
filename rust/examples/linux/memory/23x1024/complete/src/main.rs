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
                                                                              // writes MODE=0x40 (Sequential) and verifies it with RDMR

    println!("size: {} bytes", sram.size());                                  // Array size, () → u32 bytes
                                                                              // always 131072

    sram.write_byte(0x00010, 0xA5).expect("write_byte");                      // Write one byte, (address 0–131071, value 0–255) → Result<()>
                                                                              // WRITE 0x02 + 24-bit address + data
    println!("byte @0x10: 0x{:02X}", sram.read_byte(0x00010).expect("read_byte"));   // Read one byte, (address 0–131071) → Result<u8>
                                                                              // READ 0x03 + 24-bit address

    let pattern: Vec<u8> = (0..300u32).map(|i| i as u8).collect();
    sram.write(0x00100, &pattern).expect("write");                            // Block write, (address, data) → Result<()>
                                                                              // split into transactions of at most max_chunk bytes
    let mut readback = vec![0u8; pattern.len()];
    sram.read(0x00100, &mut readback).expect("read");                         // Block read, (address, buf) → Result<()>
                                                                              // sequential read across the chunk boundary
    println!("block of 300: {}", if readback == pattern { "match" } else { "MISMATCH" });

    sram.fill(0x00200, 64, 0xFF).expect("fill");                              // Fill a range, (address, length, value) → Result<()>
                                                                              // writes value over the range, chunked
    println!("fill @0x200: 0x{:02X}", sram.read_byte(0x00200).expect("read_byte"));

    sram.set_max_chunk(64);                                                   // Limit bytes per transaction, (n 1–256)
                                                                              // smaller frames for bus layers with small buffers
    println!("max_chunk: {}", sram.max_chunk());                              // Current chunk limit, () → usize bytes
    sram.set_max_chunk(256);                                                  // Limit bytes per transaction, (n 1–256)

    sram.set_mode(SRAM23X1024_MODE_PAGE).expect("set_mode");                  // Select operating mode, (mode=MODE_PAGE=0x80) → Result<()>
                                                                              // WRMR then RDMR check; accesses now wrap within 32-byte pages
    println!("mode: 0x{:02X}", sram.get_mode().expect("get_mode"));           // Read operating mode, () → Result<u8>
                                                                              // RDMR bits 7:6
    sram.write(0x00110, &pattern[..64]).expect("write");                      // Block write, (address, data) → Result<()>
                                                                              // the driver splits at page boundaries, so the data stays linear
    let mut paged = [0u8; 64];
    sram.read(0x00110, &mut paged).expect("read");                            // Block read, (address, buf) → Result<()>
    println!("page mode 64 bytes: {}", if paged[..] == pattern[..64] { "match" } else { "MISMATCH" });

    sram.set_mode(SRAM23X1024_MODE_SEQUENTIAL).expect("set_mode");            // Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → Result<()>
    sram.reset_io().expect("reset_io");                                       // Return to SPI mode, () → Result<()>
                                                                              // sends RSTIO (0xFF); harmless while already in SPI mode
}
