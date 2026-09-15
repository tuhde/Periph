use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::comms::MCP2515Minimal;
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
        .max_speed_hz(5_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut chip = MCP2515Minimal::new(device, 125, 8).expect("init MCP2515");    // Create MCP2515 driver, (spi, bitrate_kbps=125, osc_mhz=8) → Result

    let buf = chip.send(0x123, &[0xDE, 0xAD, 0xBE, 0xEF], false).expect("send");  // Send standard frame, (id=0x123, data ≤8 B, extended=false) → Result<u8>
    println!("sent on TXB{}", buf);

    let _ = chip.recv(100).expect("recv");                                          // Poll for received frame, (timeout_ms=100) → Result<Option<CanFrame>>
    println!("recv ok");
}
