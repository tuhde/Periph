use esp_hal::delay::Delay;
use esp_hal::gpio::{Level, Output};
use esp_hal::peripherals::SPI2;
use esp_hal::spi::{master::Spi, SpiMode};
use esp_hal::time::Rate;
use periph::chips::io_expander::Tpic6b595Minimal;
use embedded_hal::digital::OutputPin;

#[main]
fn main() {
    let p = esp_hal::init(esp_hal::Config::default());
    let mut delay = Delay::new();

    let spi_cfg = esp_hal::spi::master::Config::default()
        .with_frequency(Rate::from_mhz(1))
        .with_mode(SpiMode::Mode0);
    let spi = Spi::new(p.SPI2, spi_cfg)
        .unwrap()
        .with_sck(p.GPIO18)
        .with_mosi(p.GPIO23);
    let rck = Output::new(p.GPIO17, Level::Low);

    let chip = Tpic6b595Minimal::new(spi, rck, None, None, 1).expect("init TPIC6B595");
    let mut p0 = chip.pin(0);
    p0.set_low().expect("set_low");

    let _ = delay;
    loop {}
}
