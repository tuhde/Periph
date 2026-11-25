#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::{DS3502Full, Ds3502Terminal};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x28;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let delay = Delay::new();
    #[allow(non_snake_case)]
    let mut DELAY = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut pot = DS3502Full::new(i2c, ADDR, 15.0, 0.0);

    pot.init(&mut DELAY).expect("init");
    check_true!(!pot.get_mode().expect("mode"), "init_mode_sram", passed, failed);
    pot.set_wiper(100).expect("set");
    check_true!(pot.get_wiper().expect("get") == 100, "wiper_roundtrip", passed, failed);
    pot.set_wiper(300).expect("set");
    check_true!(pot.get_wiper().expect("get") == 127, "wiper_clamps_127", passed, failed);
    pot.set_voltage(6.0).expect("set V");
    check_true!((pot.get_voltage().expect("V") - 6.0).abs() < 0.1, "set_voltage", passed, failed);

    pot.set_ratio(0.5).expect("ratio");
    check_true!(pot.get_wiper().expect("get") == 64 && (pot.get_ratio().expect("ratio") - 0.5).abs() < 0.01, "ratio_half", passed, failed);
    pot.set_wiper(100).expect("set");
    let sum = pot.get_resistance(Ds3502Terminal::Low).expect("RL") + pot.get_resistance(Ds3502Terminal::High).expect("RH");
    check_true!((sum - 10000.0).abs() < 1.0, "resistance_nominal", passed, failed);
    check_true!(pot.increment(5).expect("inc") == 105 && pot.get_wiper().expect("get") == 105, "increment", passed, failed);
    check_true!(pot.increment(-500).expect("inc") == 0, "increment_clamps", passed, failed);
    pot.set_mode(true).expect("mode");
    check_true!(pot.get_mode().expect("mode"), "set_mode_eeprom", passed, failed);
    pot.set_mode(false).expect("mode");
    check_true!(!pot.get_mode().expect("mode"), "set_mode_sram", passed, failed);
    check_true!(pot.wait_ready(&mut DELAY, 30).is_ok(), "wait_ready", passed, failed);
    // store_wiper is not exercised: it consumes one of the EEPROM's limited write cycles.

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
