#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::{Ina226Full, BOL, BUL};

esp_app_desc!();

const ADDR: u8 = 0x40;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Ina226Full::new(i2c, ADDR, 0.1, 2.0).expect("init INA226");

    // Average 16 samples to smooth out noise on a noisy power rail
    chip.configure(3, 4, 4, 7).unwrap();

    // Alert if bus voltage goes outside 4.5–5.5 V (expected 5 V USB supply)
    chip.set_alert(BOL, 5.5, false, false).unwrap();
    chip.set_alert(BUL, 4.5, false, false).unwrap();

    // Sample every second; a real application would sleep between conversions
    for _ in 0..10 {
        while !chip.conversion_ready().unwrap() {
            delay.delay_ms(10);
        }

        let v = chip.voltage().unwrap();
        let i = chip.current().unwrap();
        let p = chip.power().unwrap();
        println!("V={:.3}V  I={:.4}A  P={:.4}W", v, i, p);

        if chip.overflow().unwrap() {
            println!("WARNING: math overflow — increase max_current or reduce shunt");
        }

        delay.delay_ms(1000);
    }
    loop {}
}
