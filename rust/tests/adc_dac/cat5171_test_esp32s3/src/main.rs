#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::CAT5171Full;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x2C;

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
    let mut pot = CAT5171Full::new(i2c, ADDR, 100000.0, 50.0);

    pot.set_wiper(100).expect("set");
    check_true!(pot.get_wiper().expect("get") == 100, "wiper_roundtrip", passed, failed);
    pot.set_wiper(300).expect("set");
    check_true!(pot.get_wiper().expect("get") == 255, "wiper_clamps_255", passed, failed);
    pot.set_resistance(50000.0).expect("set R");
    check_true!((pot.get_resistance().expect("R") - 50000.0).abs() < 500.0, "set_resistance", passed, failed);

    pot.set_ratio(0.5).expect("ratio");
    check_true!(pot.get_wiper().expect("get") == 128 && (pot.get_ratio().expect("ratio") - 0.5).abs() < 0.01, "ratio_half", passed, failed);
    pot.set_resistance_wa(30000.0).expect("wa");
    check_true!((pot.get_resistance_wa().expect("wa") - 30000.0).abs() < 500.0, "set_resistance_wa", passed, failed);
    pot.set_wiper(100).expect("set");
    check_true!(pot.increment(5).expect("inc") == 105 && pot.get_wiper().expect("get") == 105, "increment", passed, failed);
    check_true!(pot.increment(-500).expect("inc") == 0, "increment_clamps", passed, failed);

    pot.set_wiper(77).expect("set");
    pot.shutdown().expect("shutdown");
    check_true!(pot.is_shutdown(), "is_shutdown", passed, failed);
    check_true!(pot.get_wiper().expect("get") == 77, "shutdown_keeps_wcr", passed, failed);
    pot.resume().expect("resume");
    check_true!(!pot.is_shutdown() && pot.get_wiper().expect("get") == 77, "resume", passed, failed);

    pot.set_wiper(10).expect("set");
    pot.midscale().expect("midscale");
    check_true!(pot.get_wiper().expect("get") == 128, "midscale", passed, failed);

    pot.set_wiper(1).expect("set");
    pot.reset().expect("reset");
    check_true!(pot.get_wiper().expect("get") == 128, "reset_midscale", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
