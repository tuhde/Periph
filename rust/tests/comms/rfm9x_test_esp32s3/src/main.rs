#![no_std]
#![no_main]

use esp_hal::{delay::Delay, spi::Spi, gpio::NoPin, prelude::*};
use esp_hal::spi::dma::SpiDma;

#[entry]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi = Spi::new(peripherals.spi2, 5_000_000u32.into(), spi::MODE_0, peripherals.dma_channel_0);
    let cs = peripherals.gpio10.into_push_pull_output();

    let mut rfm = periph::chips::comms::rfm9x::RFM95Full::new(spidev, 868_000_000).expect("init RFM95");

    let mut passed = 0;
    let mut failed = 0;

    let ver = rfm.version().expect("version");
    if ver == 0x12 { passed += 1; } else { failed += 1; }
    if ver != 0 { passed += 1; } else { failed += 1; }

    rfm.send(b"test").expect("send");
    Delay::new(50_u32);

    rfm.standby().expect("standby");
    rfm.sleep().expect("sleep");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}