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

    println!("voltage: {:.3} V", chip.voltage().unwrap());          // Read bus voltage, () → f32 V
    println!("shunt_voltage: {:.6} V", chip.shunt_voltage().unwrap()); // Read shunt voltage, () → f32 V
    println!("current: {:.6} A", chip.current().unwrap());         // Read load current, () → f32 A
    println!("power: {:.6} W", chip.power().unwrap());             // Read power, () → f32 W
    println!("conversion_ready: {}", chip.conversion_ready().unwrap()); // Check conversion done, () → bool
    println!("overflow: {}", chip.overflow().unwrap());             // Check math overflow, () → bool

    chip.configure(1, 3, 0x03, 0x03, 7).unwrap();
                                                                  // Configure ADC, (brng 0–1, pga 0–3, badc 0x0F, sadc 0x0F, mode 0–7) → ()

    chip.shutdown().unwrap();           // Put chip into power-down mode, () → ()
    chip.wake().unwrap();              // Restore previous operating mode, () → ()

    chip.reset().unwrap();             // Reset all registers and re-write calibration, () → ()
    loop {}
}
