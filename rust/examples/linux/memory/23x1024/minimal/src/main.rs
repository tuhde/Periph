use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::memory::{Sram23x1024Minimal};
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

    let mut sram = Sram23x1024Minimal::new(device).expect("init 23X1024");    // Create 23X1024 driver, (spi) → Result

    let mut counter: u8 = 0;
    loop {
        sram.write_byte(0x00000, counter).expect("write_byte");              // Write one byte, (address 0–131071, value 0–255) → Result<()>
        let value = sram.read_byte(0x00000).expect("read_byte");             // Read one byte, (address 0–131071) → Result<u8>
        println!("wrote 0x{:02X}, read 0x{:02X}", counter, value);
        counter = counter.wrapping_add(1);
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
