use esp_hal::spi::master::Spi;
use esp_hal::spi::SpiMode;
use periph::chips::led::{Ws2812bMinimal, Ws2812bFull};

#[entry]
fn main() {
    let pins = esp_hal::gpio::Io::new();

    // --- Ws2812bMinimal smoke test ---
    let spi = Spi::new(
        esp_hal::peripherals::SPI2,
        esp_hal::spi::master::Config::default()
            .with_frequency(2_400_000)
            .with_mode(SpiMode::Mode0),
    )
    .with_sck(pins.pin(36))
    .with_mosi(pins.pin(35))
    .with_miso(pins.pin(37));

    let mut strip = Ws2812bMinimal::new(spi, 8);
    let ok = strip.fill(255, 0, 0).is_ok();
    if ok { esp_println::println!("PASS fill_red_accepted"); }
    else  { esp_println::println!("FAIL fill_red_accepted"); }

    let ok = strip.off().is_ok();
    if ok { esp_println::println!("PASS off_accepted"); }
    else  { esp_println::println!("FAIL off_accepted"); }

    // --- Ws2812bFull smoke test ---
    let spi2 = Spi::new(
        esp_hal::peripherals::SPI3,
        esp_hal::spi::master::Config::default()
            .with_frequency(2_400_000)
            .with_mode(SpiMode::Mode0),
    )
    .with_sck(pins.pin(36))
    .with_mosi(pins.pin(35))
    .with_miso(pins.pin(37));

    let mut full = Ws2812bFull::new(spi2, 8);
    full.set_pixel(0, 255, 0, 0);
    let ok = full.show().is_ok();
    if ok { esp_println::println!("PASS set_pixel_show_accepted"); }
    else  { esp_println::println!("FAIL set_pixel_show_accepted"); }

    full.set_brightness(128);
    let ok = full.show().is_ok();
    if ok { esp_println::println!("PASS show_brightness128_accepted"); }
    else  { esp_println::println!("FAIL show_brightness128_accepted"); }

    let ok = full.fill_hsv(0.0, 1.0, 1.0).is_ok();
    if ok { esp_println::println!("PASS fill_hsv_accepted"); }
    else  { esp_println::println!("FAIL fill_hsv_accepted"); }

    esp_println::println!("===DONE===");
    loop {}
}
