use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::AD5251Full;

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
        .unwrap_or(0x2C);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = AD5251Full::new(dev, addr, 50000.0, 75.0);

    pot.set_wiper(0, 40).expect("set");
    check_true!(pot.get_wiper(0).expect("get") == 40, "wiper0_roundtrip", passed, failed);
    pot.set_wiper(1, 60).expect("set");
    check_true!(pot.get_wiper(1).expect("get") == 60, "wiper1_roundtrip", passed, failed);
    check_true!(pot.get_wiper(0).expect("get") == 40, "wiper0_independent", passed, failed);
    pot.set_wiper(0, 300).expect("set");
    check_true!(pot.get_wiper(0).expect("get") == 63, "wiper_clamps_255", passed, failed);
    pot.set_resistance(0, 25000.0).expect("set R");
    check_true!((pot.get_resistance(0).expect("R") - 25000.0).abs() < 800.0, "set_resistance", passed, failed);

    pot.set_both(10, 58).expect("both");
    check_true!(pot.get_wiper(0).expect("get") == 10 && pot.get_wiper(1).expect("get") == 58, "set_both", passed, failed);
    pot.set_ratio(0, 0.5).expect("ratio");
    check_true!(pot.get_wiper(0).expect("get") == 32 && (pot.get_ratio(0).expect("ratio") - 0.5).abs() < 0.01, "ratio_half", passed, failed);
    pot.set_resistance_wa(1, 15000.0).expect("wa");
    check_true!((pot.get_resistance_wa(1).expect("wa") - 15000.0).abs() < 800.0, "set_resistance_wa", passed, failed);
    pot.set_wiper(0, 20).expect("set");
    pot.increment(Some(0)).expect("inc");
    check_true!(pot.get_wiper(0).expect("get") == 21, "increment", passed, failed);
    pot.decrement(Some(0)).expect("dec");
    check_true!(pot.get_wiper(0).expect("get") == 20, "decrement", passed, failed);
    pot.double(Some(0)).expect("double");
    check_true!(pot.get_wiper(0).expect("get") == 40, "double", passed, failed);
    pot.halve(Some(0)).expect("halve");
    check_true!(pot.get_wiper(0).expect("get") == 20, "halve", passed, failed);
    pot.set_both(10, 20).expect("both");
    pot.increment(None).expect("inc both");
    check_true!(pot.get_wiper(0).expect("get") == 11 && pot.get_wiper(1).expect("get") == 21, "increment_both", passed, failed);

    pot.set_both(40, 60).expect("both");
    pot.store(None, &mut Delay).expect("store");
    pot.set_both(1, 2).expect("both");
    pot.restore(None).expect("restore");
    check_true!(pot.get_wiper(0).expect("get") == 40 && pot.get_wiper(1).expect("get") == 60, "store_restore", passed, failed);
    pot.set_both(1, 2).expect("both");
    pot.reset(&mut Delay).expect("reset");
    check_true!(pot.get_wiper(0).expect("get") == 40 && pot.get_wiper(1).expect("get") == 60, "reset_restores_eemem", passed, failed);

    pot.write_eemem(15, 0xA5, &mut Delay).expect("write_eemem");
    check_true!(pot.read_eemem(15).expect("read_eemem") == 0xA5, "eemem_roundtrip", passed, failed);
    let tol = pot.read_tolerance(0).expect("tol");
    check_true!(tol > -50.0 && tol < 50.0, "tolerance_range", passed, failed);
    check_true!((pot.rab_actual(0).expect("rab") - 50000.0 * (1.0 + tol / 100.0)).abs() < 0.5, "rab_actual", passed, failed);
    pot.nop().expect("nop");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
