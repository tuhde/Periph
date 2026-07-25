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

    // --- Indoor comfort monitor ---
    // Reads temperature and humidity every 5 seconds and prints a one-line
    // status with a comfort assessment. Demonstrates reliable real-world
    // polling with retry-based error recovery.
    for _ in 0..60 {
        match dht.read_retry(3) {                  // Read with retries, (max_retries=3) → (f32 °C, f32 %RH)
            Ok((t, h)) => println!("{} C, {} %RH, {}", t, h, comfort(h)),
            Err(_) => {
                // --- Handle read failure ---
                // After all retries are exhausted, log a warning and continue.
                // The next loop iteration will try again with a fresh sample.
                println!("WARN: DHT11 read failed after retries");
            }
        }
        delay.delay_ms(5000);
    }
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
