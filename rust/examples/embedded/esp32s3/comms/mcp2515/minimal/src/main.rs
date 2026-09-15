#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::comms::MCP2515Minimal;

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

    let mut chip = MCP2515Minimal::new(device, 125, 8).expect("init MCP2515");    // Create MCP2515 driver, (spi, bitrate_kbps=125, osc_mhz=8) → Result

    let buf = chip.send(0x123, &[0xDE, 0xAD, 0xBE, 0xEF], false).expect("send");  // Send standard frame, (id=0x123, data ≤8 B, extended=false) → Result<u8>
    println!("sent on TXB{}", buf);

    let _ = chip.recv(100).expect("recv");                                          // Poll for received frame, (timeout_ms=100) → Result<Option<CanFrame>>
    println!("recv ok");

    let mut delay = Delay::new();
    delay.delay_ms(250);
    loop {}
}
