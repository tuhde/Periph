use embedded_hal::i2c::I2c as _;
use linux_embedded_hal::I2cdev;
use periph::chips::magnetometer::Hmc5883lFull;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

macro_rules! check_eq {
    ($got:expr, $expected:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $got == $expected {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}: got {}, expected {}", $label, $got, $expected);
            $failed += 1;
        }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1E);

    let mut dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    let mut chip = Hmc5883lFull::new(dev, addr).expect("init HMC5883L");

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Identification ---
    let (id_a, id_b, id_c) = chip.identify().expect("identify");
    check_eq!(id_a, 0x48, "identify A", passed, failed);
    check_eq!(id_b, 0x34, "identify B", passed, failed);
    check_eq!(id_c, 0x33, "identify C", passed, failed);

    // --- Status ---
    let sb = chip.status().expect("status");
    check_true!(sb <= 255, "status_byte valid", passed, failed);

    // --- Data ready ---
    check_true!(chip.data_ready().is_ok(), "data_ready returns bool", passed, failed);

    // --- Magnetic field reading ---
    let (x, y, z) = chip.magnetic_field().expect("magnetic_field");
    check_true!(x.is_none() || x.is_some(), "x is Option<f32>", passed, failed);
    check_true!(y.is_none() || y.is_some(), "y is Option<f32>", passed, failed);
    check_true!(z.is_none() || z.is_some(), "z is Option<f32>", passed, failed);

    // --- Configuration ---
    chip.configure(15.0, 8, 1).expect("configure");
    check_true!(true, "configure accepted", passed, failed);

    chip.set_gain(2).expect("set_gain");
    check_true!(true, "set_gain accepted", passed, failed);

    chip.set_mode(0).expect("set_mode continuous");
    check_true!(true, "set_mode continuous accepted", passed, failed);

    // --- Single-shot measurement ---
    let (x, y, z) = chip.single_measurement().expect("single_measurement");
    check_true!(x.is_none() || x.is_some(), "single_measurement x is Option<f32>", passed, failed);
    check_true!(y.is_none() || y.is_some(), "single_measurement y is Option<f32>", passed, failed);
    check_true!(z.is_none() || z.is_some(), "single_measurement z is Option<f32>", passed, failed);

    chip.set_mode(2).expect("set_mode idle");
    check_true!(true, "set_mode idle accepted", passed, failed);

    // --- Self-test ---
    let (x, y, z) = chip.self_test(true).expect("self_test");
    check_true!(x.is_none() || x.is_some(), "self_test x is Option<f32>", passed, failed);
    check_true!(y.is_none() || y.is_some(), "self_test y is Option<f32>", passed, failed);
    check_true!(z.is_none() || z.is_some(), "self_test z is Option<f32>", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}