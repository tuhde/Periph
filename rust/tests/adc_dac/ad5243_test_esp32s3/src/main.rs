#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::AD5243Full;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x2F;

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
    let mut pot = AD5243Full::new(i2c, ADDR, 10000.0, 160.0);

    pot.set_wiper(0, 100).expect("set");
    check_true!(pot.get_wiper(0).expect("get") == 100, "wiper0_roundtrip", passed, failed);
    pot.set_wiper(1, 200).expect("set");
    check_true!(pot.get_wiper(1).expect("get") == 200, "wiper1_roundtrip", passed, failed);
    check_true!(pot.get_wiper(0).expect("get") == 100, "wiper0_independent", passed, failed);
    pot.set_wiper(0, 300).expect("set");
    check_true!(pot.get_wiper(0).expect("get") == 255, "wiper_clamps_255", passed, failed);
    pot.set_resistance(0, 5000.0).expect("set R");
    check_true!((pot.get_resistance(0).expect("R") - 5000.0).abs() < 50.0, "set_resistance", passed, failed);

    pot.set_both(10, 250).expect("both");
    check_true!(pot.get_wiper(0).expect("get") == 10 && pot.get_wiper(1).expect("get") == 250, "set_both", passed, failed);
    pot.set_ratio(0, 0.5).expect("ratio");
    check_true!(pot.get_wiper(0).expect("get") == 128 && (pot.get_ratio(0).expect("ratio") - 0.5).abs() < 0.01, "ratio_half", passed, failed);
    pot.set_resistance_wa(1, 3000.0).expect("wa");
    check_true!((pot.get_resistance_wa(1).expect("wa") - 3000.0).abs() < 50.0, "set_resistance_wa", passed, failed);
    pot.set_wiper(0, 100).expect("set");
    check_true!(pot.increment(0, 5).expect("inc") == 105 && pot.get_wiper(0).expect("get") == 105, "increment", passed, failed);
    check_true!(pot.increment(0, -500).expect("inc") == 0, "increment_clamps", passed, failed);

    pot.set_wiper(1, 77).expect("set");
    pot.shutdown(1).expect("shutdown");
    check_true!(pot.is_shutdown(1).expect("sd"), "is_shutdown", passed, failed);
    check_true!(pot.get_wiper(1).expect("get") == 77, "shutdown_keeps_rdac", passed, failed);
    pot.resume(1).expect("resume");
    check_true!(!pot.is_shutdown(1).expect("sd") && pot.get_wiper(1).expect("get") == 77, "resume", passed, failed);

    pot.set_both(1, 2).expect("both");
    pot.reset().expect("reset");
    check_true!(pot.get_wiper(0).expect("get") == 128 && pot.get_wiper(1).expect("get") == 128, "reset_midscale", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
