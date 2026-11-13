use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::adc_dac::{DS3502Full, Ds3502Terminal};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    #[allow(non_snake_case)]
    let mut DELAY = Delay;
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x28);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = DS3502Full::new(dev, addr, 15.0, 0.0);

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
    if failed > 0 { std::process::exit(1); }
}
