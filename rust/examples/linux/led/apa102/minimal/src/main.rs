//! APA102 minimal example — fill entire strip with solid colours.
use linux_embedded_hal::SpidevBus;
use periph::connection::spi::SpiConnection;
use periph::chips::led::Apa102Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let bus = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let device = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);

    let mut spidev = linux_embedded_hal::Spidev::open(format!("/dev/spidev{bus}.{device}")).unwrap();
    spidev.configure(
        &linux_embedded_hal::SpidevOptions::new()
            .max_speed_hz(1_000_000)
            .mode(spidev::SpiModeFlags::SPI_MODE_0)
            .build(),
    ).unwrap();
    let spi_bus = SpidevBus(spidev);
    let spi = embedded_hal_bus::spi::ExclusiveDevice::new_no_delay(spi_bus, None).unwrap();

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