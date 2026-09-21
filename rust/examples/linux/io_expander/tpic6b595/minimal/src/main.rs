use linux_embedded_hal::{CdevPin, SpidevBus};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use periph::chips::io_expander::Tpic6b595Minimal;
use embedded_hal::digital::OutputPin;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let spi_bus: u8 = std::env::var("SIPO_SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SIPO_SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let rck_line: u32 = std::env::var("SIPO_RCK").ok().and_then(|v| v.parse().ok()).unwrap_or(5);

    let dev_path = format!("/dev/spidev{}.{}", spi_bus, spi_device);
    let mut spi_dev = Spidev::open(dev_path).expect("open spidev");
    spi_dev.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spidev");

    let rck = CdevPin::open("/dev/gpiochip0", rck_line, false).expect("open rck line");

    let chip = Tpic6b595Minimal::new(SpidevBus(spi_dev), rck, None, None, 1)        // Create TPIC6B595 driver, (spi, rck, srclr=None, g=None, num_devices=1) → Result
        .expect("init TPIC6B595");
    let mut p0 = chip.pin(0);                                          // Get pin proxy, (n=0) → ExPin
    let mut p7 = chip.pin(7);                                          // Get pin proxy, (n=7) → ExPin

    loop {
        p0.set_high().expect("set_high");                               // Set DMOS output ON, () → Result<(), E>
        p7.set_low().expect("set_low");                                 // Set DMOS output OFF, () → Result<(), E>
        sleep(Duration::from_millis(500));
        p0.set_low().expect("set_low");                                 // Set DMOS output OFF, () → Result<(), E>
        p7.set_high().expect("set_high");                               // Set DMOS output ON, () → Result<(), E>
        sleep(Duration::from_millis(500));
    }
}
