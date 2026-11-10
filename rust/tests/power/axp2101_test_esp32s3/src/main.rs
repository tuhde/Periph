#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::power::{Axp2101Full, Axp2101Minimal};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const SDA_PIN: u8 = 1;
const SCL_PIN: u8 = 2;
const ADDR: u8 = 0x34;

#[main]
fn main() -> ! {
    let mut peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0.reborrow(), Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1.reborrow())
        .with_scl(peripherals.GPIO2.reborrow());

    let delay = Delay::new();
    let mut chip = Axp2101Minimal::new(i2c, ADDR).expect("init AXP2101");
    delay.delay_millis(20);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let v = chip.battery_voltage().expect("battery_voltage");
    let pct = chip.battery_percent().expect("battery_percent");
    let state = chip.charge_state().expect("charge_state");
    check_true!(v >= 0.0 && v <= 5.0, "battery_voltage_plausible", passed, failed);
    check_true!(pct <= 100, "battery_percent_range", passed, failed);
    check_true!(state <= 5, "charge_state_range", passed, failed);
    check_true!(chip.vbus_present().is_ok(), "vbus_present_no_error", passed, failed);

    drop(chip);
    let i2c = I2c::new(peripherals.I2C0.reborrow(), Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1.reborrow())
        .with_scl(peripherals.GPIO2.reborrow());
    let mut full = Axp2101Full::new(i2c, ADDR).expect("init AXP2101 Full");
    check_true!(full.vbus_voltage().map(|x| x >= 0.0 && x <= 15.0).unwrap_or(false), "vbus_voltage_range", passed, failed);
    check_true!(full.system_voltage().map(|x| x >= 0.0 && x <= 6.0).unwrap_or(false), "system_voltage_range", passed, failed);
    check_true!(full.die_temperature().map(|x| x >= -40.0 && x <= 150.0).unwrap_or(false), "die_temperature_range", passed, failed);
    check_true!(full.read_all().is_ok(), "read_all", passed, failed);
    check_true!(full.get_rail("dcdc1").map(|(_, volts)| volts >= 1.4 && volts <= 3.5).unwrap_or(false), "get_rail_dcdc1", passed, failed);
    check_true!(full.write_data_buffer(0, 0x5A).is_ok(), "write_data_buffer", passed, failed);
    check_true!(full.read_data_buffer(0).map(|b| b == 0x5A).unwrap_or(false), "data_buffer_roundtrip", passed, failed);
    check_true!(full.poll_interrupt().is_ok(), "poll_interrupt", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop { Delay::new().delay_millis(1000); }
}
