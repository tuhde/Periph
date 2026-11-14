use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::environmental::AM2315Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = AM2315Full::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (model, _version, _id) = chip.read_device_info(&mut delay).expect("device info");
    check_true!(model != 0xFFFF, "device info model");

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t >= -40.0 && t <= 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") <= 100.0, "read_humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") >= -40.0, "read_temperature range");
    let (rr, rt) = chip.read_raw(&mut delay).expect("raw");
    check_true!(rr <= 1000 && (rt & 0x7FFF) <= 1250, "raw ranges");
    let (ft, fh) = chip.read_fresh(&mut delay).expect("fresh");
    check_true!(ft >= -40.0 && fh <= 100.0, "read_fresh range");
    let mut regs = [0u8; 4];
    check_true!(chip.read_registers(0x00, &mut regs, &mut delay).is_ok(), "read_registers ok");

    chip.write_user_registers(0x1234, 0xABCD, &mut delay).expect("write user");
    check_true!(chip.read_user_registers(&mut delay).expect("read user") == (0x1234, 0xABCD), "user registers round trip");
    chip.write_user_registers(0, 0, &mut delay).expect("clear user");
    chip.wake(&mut delay);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
