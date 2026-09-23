use linux_embedded_hal::I2cdev;
use periph::chips::motor::{Drv8830Direction, Drv8830Full, Drv8830Minimal};

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
        .unwrap_or(0x60);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut motor = Drv8830Minimal::new(dev, addr).expect("init DRV8830");
    motor.stop().expect("stop");
    let dev = motor.release();

    let mut full = Drv8830Full::new(dev, addr).expect("init DRV8830 Full");
    full.drive(2.0).expect("drive");
    let out = full.read_output().expect("read_output");
    check_true!(out.direction == Drv8830Direction::Forward, "drive_forward_direction", passed, failed);
    check_true!((out.voltage - 2.0).abs() < 0.1, "drive_forward_voltage", passed, failed);

    full.drive(-1.0).expect("drive");
    check_true!(full.read_output().expect("read_output").direction == Drv8830Direction::Reverse, "drive_reverse_direction", passed, failed);

    full.brake().expect("brake");
    check_true!(full.read_output().expect("read_output").direction == Drv8830Direction::Brake, "brake_direction", passed, failed);

    full.stop().expect("stop");
    check_true!(full.read_output().expect("read_output").direction == Drv8830Direction::Coast, "stop_direction", passed, failed);

    full.clear_fault().expect("clear_fault");
    check_true!(!full.read_fault().expect("read_fault").fault, "clear_fault", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
