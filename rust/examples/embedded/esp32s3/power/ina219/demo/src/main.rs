#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::Ina219Full;

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

    let mut chip = Ina219Full::new(i2c, ADDR, 0.1, 2.0).expect("init INA219");

    // --- Configure for noise-sensitive power rail monitoring ---
    // 128-sample averaging suppresses switching noise on a noisy 5 V rail;
    // continuous mode avoids re-triggering overhead between measurements.
    chip.configure(1, 3, 0x0F, 0x0F, 7).unwrap();
                                                           // Configure ADC, (brng 0–1, pga 0–3, badc 0x0F, sadc 0x0F, mode 0–7) → ()

    for _ in 0..10 {
        while !chip.conversion_ready().unwrap() {          // Check conversion done, () → bool
            delay.delay_ms(10);
        }

        let v = chip.voltage().unwrap();                   // Read bus voltage, () → f32 V
        let i = chip.current().unwrap();                   // Read load current, () → f32 A
        let p = chip.power().unwrap();                     // Read power, () → f32 W
        println!("V={:.3}V  I={:.4}A  P={:.4}W", v, i, p);

        delay.delay_ms(1000);
    }
    loop {}
}
