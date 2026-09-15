#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::comms::Rfm95Minimal;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High);
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let mut radio = Rfm95Minimal::new(device, 868_000_000).expect("init RFM95");    // Create RFM95W driver, (spi, frequency_hz=868e6) → Result

    radio.send(b"hello").expect("send");                                              // Send packet, (data=&[u8] ≤255 B) → Result<()>
    println!("sent");

    let mut delay = Delay::new();
    delay.delay_ms(250);
    loop {}
}
