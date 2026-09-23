//! DHT11 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO4 -> DHT11 DATA, with a 4.7 kΩ pull-up to 3V3.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Flex;
use esp_hal::main;
use esp_println::println;
use periph::chips::humidity::Dht11MinimalEsp32s3;
use periph::connection::dhtxx::DHTxxConnectionEsp32s3;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let pin = Flex::new(peripherals.GPIO4);
    let connection = DHTxxConnectionEsp32s3::new(pin);
    let mut dht = Dht11MinimalEsp32s3::new(connection);

    // The sensor needs ~1 s after power-up and >= 1 s between reads; retry a
    // few times so a single checksum glitch does not fail the run.
    delay.delay_millis(1500);
    let mut reading = None;
    for _ in 0..3 {
        let result = dht.read(|ms| Delay::new().delay_millis(ms), |us| Delay::new().delay_micros(us));
        if let Ok(r) = result {
            reading = Some(r);
            break;
        }
        delay.delay_millis(2000);
    }
    check_true!(reading.is_some(), "read_ok", passed, failed);
    if let Some((t, h)) = reading {
        println!("temperature={} C humidity={} %RH", t, h);
        check_true!((0.0..=50.0).contains(&t), "temperature_in_range", passed, failed);
        check_true!((20.0..=90.0).contains(&h), "humidity_in_range", passed, failed);
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
