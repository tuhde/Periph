#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::power::{Ina260Full};
use periph::chips::power::ina260::{MODE_SHUNT_BUS_TRIGGERED, CNVR, BOL, BUL, OCL, UCL, POL};

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

    let mut chip = Ina260Full::new(i2c, ADDR).expect("init INA260");

    println!("manufacturer_id: 0x{:04X}", chip.manufacturer_id().unwrap());
    println!("die_id: 0x{:04X}", chip.die_id().unwrap());

    chip.configure(3, 4, 4, 7).unwrap();

    println!("voltage: {:.3} V", chip.voltage().unwrap());
    println!("current: {:.6} A", chip.current().unwrap());
    println!("power: {:.6} W", chip.power().unwrap());

    println!("conversion_ready: {}", chip.conversion_ready().unwrap());
    println!("overflow: {}", chip.overflow().unwrap());

    chip.set_alert(BOL, 5.0, false, false).unwrap();
    println!("alert_flags after BOL set: 0x{:04X}", chip.alert_flags().unwrap());

    chip.set_alert(OCL, 5.0, false, false).unwrap();
    chip.set_alert(UCL, -5.0, false, false).unwrap();
    chip.set_alert(BUL, 1.0, false, false).unwrap();
    chip.set_alert(POL, 1.0, false, false).unwrap();

    chip.set_alert(CNVR, 0.0, false, false).unwrap();
    chip.clear_alert().unwrap();

    chip.trigger(MODE_SHUNT_BUS_TRIGGERED).unwrap();
    while !chip.conversion_ready().unwrap() {}

    chip.shutdown().unwrap();
    println!("shutdown done");

    chip.wake().unwrap();
    println!("wake done");

    chip.reset().unwrap();
    println!("reset done");
    loop {}
}
