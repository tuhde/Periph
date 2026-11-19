#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::Mpl115a2Minimal;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut baro = Mpl115a2Minimal::new(i2c, 0x60).expect("init MPL115A2"); // Create MPL115A2 driver, (i2c, addr=0x60) → Result

    for _ in 0..5 {
        let p = baro.read_pressure(&mut delay).expect("read pressure");       // Read pressure, (delay) → f32 Pa
        let t = baro.read_temperature(&mut delay).expect("read temperature"); // Read temperature, (delay) → f32 C (approximate)
        println!("{:.0} Pa, {:.1} C", p, t);
        delay.delay_millis(1000);
    }
    loop {}
}
