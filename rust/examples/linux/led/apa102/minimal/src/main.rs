//! APA102 minimal example — fill entire strip with solid colours.
use linux_embedded_hal::SpidevBus;
use spidev::{Spidev, SpidevOptions, SpiModeFlags};
use embedded_hal_bus::spi::ExclusiveDevice;

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}
use periph::chips::led::Apa102Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let bus = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let device = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);

    let mut spidev = Spidev::open(format!("/dev/spidev{bus}.{device}")).unwrap();
    spidev.configure(
        &SpidevOptions::new()
            .max_speed_hz(1_000_000)
            .mode(SpiModeFlags::SPI_MODE_0)
            .build(),
    ).unwrap();
    let spi_bus = SpidevBus(spidev);
    let spi = ExclusiveDevice::new_no_delay(spi_bus, NullCs).unwrap();

    let mut strip = Apa102Minimal::new(spi, 30);

    loop {
        strip.fill(255, 0, 0).unwrap();   // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → ()
        sleep(Duration::from_secs(1));
        strip.fill(0, 255, 0).unwrap();   // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → ()
        sleep(Duration::from_secs(1));
        strip.fill(0, 0, 255).unwrap();   // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → ()
        sleep(Duration::from_secs(1));
        strip.off().unwrap();             // Turn off all pixels, () → ()
        sleep(Duration::from_secs(1));
    }
}