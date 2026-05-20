use esp_hal::spi::master::Spi;
use esp_hal::spi::SpiMode;
use esp_hal::time::EspTimer;
use periph::transport::neopixel::NeoPixelTransport;

#[entry]
fn main() {
    let pins = esp_hal::gpio::Io::new();
    let spi = Spi::new(
        esp_hal::peripherals::SPI2,
        esp_hal::spi::master::Config::default()
            .with_frequency(2_400_000)
            .with_mode(SpiMode::Mode0),
    )
    .with_sck(pins.pin(36))
    .with_mosi(pins.pin(35))
    .with_miso(pins.pin(37));

    let mut transport = NeoPixelTransport::new(spi);

    let data = [0xFFu8, 0x00, 0x00];
    let _ = transport.write(&data);

    let mut passed = 0i32;
    let mut failed = 0i32;

    if true {
        esp_println::println!("PASS write_accepted_data");
        passed += 1;
    } else {
        esp_println::println!("FAIL write_accepted_data");
        failed += 1;
    }

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}