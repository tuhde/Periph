use linux_embedded_hal::SpidevBus;
use periph::chips::led::Ws2812bMinimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let spi_bus: u8    = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let n_pixels: usize = std::env::var("N_PIXELS").ok().and_then(|v| v.parse().ok()).unwrap_or(30);

    let spi = SpidevBus::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
        .expect("open spidev");
    let mut strip = Ws2812bMinimal::new(spi, n_pixels);          // Create WS2812B driver, (spi, n=pixel count) → Self

    loop {
        strip.fill(255, 0, 0).expect("fill red");                // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → Result<(), E>
        sleep(Duration::from_secs(1));
        strip.fill(0, 255, 0).expect("fill green");              // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → Result<(), E>
        sleep(Duration::from_secs(1));
        strip.fill(0, 0, 255).expect("fill blue");               // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → Result<(), E>
        sleep(Duration::from_secs(1));
        strip.off().expect("off");                               // Turn off all pixels, () → Result<(), E>
        sleep(Duration::from_secs(1));
    }
}
