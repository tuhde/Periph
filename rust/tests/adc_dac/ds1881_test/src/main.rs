use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{DS1881Full, DS1881Minimal};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x28);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = DS1881Minimal::new(dev, addr);
    pot.init(&mut Delay).expect("init");
    pot.set_attenuation(0, 20.0).expect("set");
    pot.set_attenuation(1, 33.0).expect("set");
    check_true!(pot.get_position(0).expect("get") == 20 && pot.get_position(1).expect("get") == 33, "position_roundtrip", passed, failed);
    check_true!(pot.get_attenuation(0).expect("get") == 20.0 && pot.get_attenuation(1).expect("get") == 33.0, "attenuation_roundtrip", passed, failed);
    pot.mute(0).expect("mute");
    check_true!(pot.get_position(0).expect("get") == 63 && pot.get_attenuation(0).expect("get") == 80.0, "mute", passed, failed);
    pot.set_position(1, 5).expect("set");
    check_true!(pot.get_position(1).expect("get") == 5, "set_position", passed, failed);
    let dev = pot.release();

    let mut full = DS1881Full::new(dev, addr);
    full.init(&mut Delay).expect("init");
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
    if failed > 0 { std::process::exit(1); }
}
