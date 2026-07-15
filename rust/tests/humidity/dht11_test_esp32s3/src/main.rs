#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Input, Output, Pull};
use esp_println::println;
use periph::chips::humidity::Dht11Full;
use periph::transport::dht11::DHT11Transport;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let mut passed = 0i32;
    let mut failed = 0i32;

    // DHT11 DATA line on GPIO4 (input + output handles)
    let data_in  = Input::new(peripherals.GPIO4, Pull::None);
    let data_out = Output::new(peripherals.GPIO4, esp_hal::gpio::Level::Low);
    let transport = DHT11Transport::new(data_in, data_out);
    let mut chip  = Dht11Full::new(transport);
    let mut delay = Delay::new();

    let result = chip.read_retry(&mut delay, 3).expect("read_retry");
    check_true!(result.0 >= -20.0 && result.0 <= 60.0, "read_retry temperature in [-20, 60]", passed, failed);
    check_true!(result.1 >=   0.0 && result.1 <= 100.0, "read_retry humidity in [0, 100]", passed, failed);

    let raw = chip.read_raw(&mut delay).expect("read_raw");
    check_true!(raw.len() == 5, "read_raw length is 5", passed, failed);
    let checksum = raw[0].wrapping_add(raw[1]).wrapping_add(raw[2]).wrapping_add(raw[3]);
    check_true!(checksum == raw[4], "read_raw checksum OK", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
