#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::comms::Rfm95Full;

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

    let mut passed = 0u32;
    let mut failed = 0u32;

    let mut radio = Rfm95Full::new(device, 868_000_000).expect("init RFM95");

    let ver = radio.inner.inner.version().unwrap_or(0xFF);
    if ver == 0x12 { println!("PASS version == 0x12"); passed += 1; }
    else           { println!("FAIL version"); failed += 1; }

    radio.inner.inner.configure(7, 125.0, 5).expect("configure");
    println!("PASS configure accepted"); passed += 1;

    radio.inner.inner.standby().expect("standby");
    println!("PASS standby accepted"); passed += 1;

    radio.inner.inner.send(b"test123").expect("send");
    println!("PASS send accepted"); passed += 1;

    radio.inner.inner.sleep().expect("sleep");
    println!("PASS sleep accepted"); passed += 1;

    radio.inner.inner.standby().expect("wake");
    println!("PASS wake accepted"); passed += 1;

    println!("===DONE: {} passed, {} failed===", passed, failed);

    let mut delay = Delay::new();
    delay.delay_ms(250);
    loop {}
}
