#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::color::AS7262Minimal;

esp_app_desc!();

const ADDR: u8 = 0x49;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create AS7262 driver, (i2c, addr=0x49) → AS7262
    let mut chip = AS7262Minimal::new(dev, addr);
    // Reset and configure defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    loop {
        // Read six calibrated channels, (delay) → Result<Channels<f32>>
        let c = chip.read(&mut delay).expect("read");
        println!("V {:.1} B {:.1} G {:.1} Y {:.1} O {:.1} R {:.1}", c.violet, c.blue, c.green, c.yellow, c.orange, c.red);
        // Read device temperature, (delay) → Result<f32 °C>
        println!("{:.0} C", chip.temperature(&mut delay).expect("temperature"));
        sleep_ms!(2000);
    }
}
