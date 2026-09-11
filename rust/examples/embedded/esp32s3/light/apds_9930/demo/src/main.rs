// Demo for the APDS-9930 — adaptive backlight + screen-lock scenario.

#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::Apds9930Full;

esp_app_desc!();

const DIM_LUX_THRESHOLD: f32 = 10.0;
const PROX_SCREEN_OFF: u16 = 400;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());                     // Init ESP HAL, () → Peripherals
    let mut delay = Delay::new();                                                     // Create delay provider, () → Delay
    let i2c = I2c::new(peripherals.I2C0, Config::default())                           // Create I²C bus, (I2C0, config=default) → I2c
        .unwrap()
        .with_sda(peripherals.GPIO8)
        .with_scl(peripherals.GPIO9);
    let mut chip = Apds9930Full::new(i2c, 0x39, &mut delay).unwrap();                 // Construct APDS-9930 Full, (i2c, addr=0x39, delay) → Result<Apds9930Full, _>
                                                                                       // default 101 ms ALS integration, 8-pulse proximity, 100 mA drive

    // --- Sample lux and proximity once per second ---
    loop {
        delay.delay_ms(1000);
        let lx = chip.lux().unwrap();                                                  // Read ambient illuminance, () → Result<f32, _> lx
                                                                                       // IR-compensated lux via Ch0/Ch1 difference
        let p  = chip.proximity().unwrap();                                            // Read proximity count, () → Result<u16, _> count
                                                                                       // 16-bit ADC value; higher = closer
        println!("lux={:.1} lx  proximity={}", lx, p);
        if lx < DIM_LUX_THRESHOLD {
            println!("  -> dim backlight");
        }
        if p > PROX_SCREEN_OFF {
            println!("  -> disable screen");
        }
    }
}