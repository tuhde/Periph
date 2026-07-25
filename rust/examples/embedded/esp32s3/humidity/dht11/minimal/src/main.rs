#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_println::println;
use periph::chips::humidity::Dht11MinimalEsp32s3;
use periph::transport::dhtxx::DHTxxTransportEsp32s3;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let pin = peripherals.GPIO4;
    let transport = DHTxxTransportEsp32s3::new(pin);
    let delay = Delay::new();

    let mut dht = Dht11MinimalEsp32s3::new(transport);  // Create DHT11 driver, (transport)

    for _ in 0..5 {
        match dht.read(&mut |ms| delay.delay_millis(ms), &mut |us| delay.delay_micros(us)) {                        // Read temperature & humidity, () → (f32 °C, f32 %RH)
            Ok((t, h)) => println!("{} C, {} %RH", t, h),
            Err(e)     => println!("read error: {:?}", e),
        }
        delay.delay_ms(2000);
    }
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
