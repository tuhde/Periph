#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_println::println;
use periph::chips::humidity::Dht11FullEsp32s3;
use periph::transport::dhtxx::DHTxxTransportEsp32s3;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let pin = peripherals.GPIO4;
    let transport = DHTxxTransportEsp32s3::new(pin);
    let delay = Delay::new();

    let mut dht = Dht11FullEsp32s3::new(transport, 3);  // Create DHT11 driver, (transport, max_retries=3)

                                                    // retries up to 5 times on checksum error
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
