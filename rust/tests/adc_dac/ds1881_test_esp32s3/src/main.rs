#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::{DS1881Full, DS1881Minimal};

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
    let mut delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut pot = DS1881Minimal::new(i2c, ADDR);
    pot.init(&mut delay).expect("init");
    pot.set_attenuation(0, 20.0).expect("set");
    pot.set_attenuation(1, 33.0).expect("set");
    check_true!(pot.get_position(0).expect("get") == 20 && pot.get_position(1).expect("get") == 33, "position_roundtrip", passed, failed);
    check_true!(pot.get_attenuation(0).expect("get") == 20.0 && pot.get_attenuation(1).expect("get") == 33.0, "attenuation_roundtrip", passed, failed);
    pot.mute(0).expect("mute");
    check_true!(pot.get_position(0).expect("get") == 63 && pot.get_attenuation(0).expect("get") == 80.0, "mute", passed, failed);
    pot.set_position(1, 5).expect("set");
    check_true!(pot.get_position(1).expect("get") == 5, "set_position", passed, failed);
    let dev = pot.release();

    let mut full = DS1881Full::new(dev, ADDR);
    full.init(&mut delay).expect("init");
    let cfg = full.get_config().expect("config");
    check_true!(cfg.option == 1 && cfg.zero_crossing && !cfg.nonvolatile, "config_default", passed, failed);
    full.set_both(10, 40).expect("both");
    check_true!({ let r = full.read_all().expect("all"); r.0 == 10 && r.1 == 40 }, "set_both", passed, failed);
    full.set_attenuation_both(15.0, None).expect("both");
    check_true!({ let r = full.read_all().expect("all"); r.0 == 15 && r.1 == 15 }, "set_attenuation_both", passed, failed);
    full.set_zero_crossing(false).expect("zc");
    check_true!(!full.get_config().expect("config").zero_crossing, "zero_crossing_off", passed, failed);
    full.set_zero_crossing(true).expect("zc");
    full.set_option(2).expect("option");
    check_true!(full.get_config().expect("config").option == 2 && full.max_position() == 32, "option2", passed, failed);
    full.set_both(13, 33).expect("both");
    check_true!({ let r = full.read_all().expect("all"); r.0 == 13 && r.1 == 33 } && full.position_to_db(13) == 14.0, "option2_positions", passed, failed);
    full.set_option(1).expect("option");
    check_true!(full.get_config().expect("config").option == 1, "option1_again", passed, failed);
    full.reset().expect("reset");
    check_true!({ let r = full.read_all().expect("all"); r.0 == 63 && r.1 == 63 }, "reset_mutes", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
