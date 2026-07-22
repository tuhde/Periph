// ESP32-S3 DHT11 smoke test.
//
// Reads temperature and humidity from a DHT11 sensor and prints the result.
// Requires the GPIO pin (default GPIO4) to be wired to the DHT11 DATA line
// with a 4.7 kΩ pull-up to 3V3.

#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_hal::{delay::Delay, gpio::AnyFlex, main, peripherals::Peripherals};
use periph::chips::humidity::Dht11MinimalEsp32s3;
use periph::transport::dhtxx::DHTxxTransportEsp32s3;

#[main]
fn main() -> ! {
    let peripherals = Peripherals::take();
    let pin: AnyFlex = peripherals.GPIO4;
    let transport = DHTxxTransportEsp32s3::new(pin);
    let mut dht = Dht11MinimalEsp32s3::new(transport);
    let delay = Delay::new();

    loop {
        let mut delay_ms = |ms: u32| delay.delay_millis(ms);
        let mut delay_us = |us: u32| { let d = Delay::new(); d.delay_micros(us); };
        match dht.read(&mut delay_ms, &mut delay_us) {
            Ok((t, h)) => {
                esp_println::println!("{} C, {} %RH", t, h);
            }
            Err(e) => esp_println::println!("read error: {:?}", e),
        }
        delay.delay_millis(2000);
    }
}
