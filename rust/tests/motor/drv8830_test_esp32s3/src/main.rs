#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::motor::{Drv8830Direction, Drv8830Full};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x60;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut motor = Drv8830Full::new(i2c, ADDR).expect("init DRV8830");

    motor.drive(2.0).expect("drive");
    delay.delay_millis(100);
    let out = motor.read_output().expect("read_output");
    check_true!(out.direction == Drv8830Direction::Forward, "drive_forward_direction", passed, failed);
    check_true!(out.voltage > 1.9 && out.voltage < 2.1, "drive_forward_voltage", passed, failed);

    motor.drive(-1.0).expect("drive");
    check_true!(motor.read_output().expect("read_output").direction == Drv8830Direction::Reverse, "drive_reverse_direction", passed, failed);

    motor.brake().expect("brake");
    check_true!(motor.read_output().expect("read_output").direction == Drv8830Direction::Brake, "brake_direction", passed, failed);

    motor.stop().expect("stop");
    check_true!(motor.read_output().expect("read_output").direction == Drv8830Direction::Coast, "stop_direction", passed, failed);

    motor.clear_fault().expect("clear_fault");
    check_true!(!motor.read_fault().expect("read_fault").fault, "clear_fault", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
