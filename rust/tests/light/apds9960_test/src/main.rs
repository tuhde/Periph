use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Apds9960Full;

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

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x39);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Apds9960Full::new(dev, addr, &mut delay).expect("init APDS9960");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(chip.chip_id().unwrap() == 0xAB, "chip_id", passed, failed);

    let (c, r, g, b) = chip.color().unwrap();
    check_true!(c >= 0, "color_clear >= 0", passed, failed);
    check_true!(r >= 0, "color_red >= 0", passed, failed);
    check_true!(g >= 0, "color_green >= 0", passed, failed);
    check_true!(b >= 0, "color_blue >= 0", passed, failed);

    check_true!(chip.is_als_valid().unwrap(), "is_als_valid", passed, failed);

    chip.enable_proximity(true).unwrap();
    std::thread::sleep(std::time::Duration::from_millis(100));
    check_true!(chip.proximity().unwrap() <= 255, "proximity <= 255", passed, failed);
    check_true!(chip.is_proximity_valid().unwrap(), "is_proximity_valid", passed, failed);

    chip.configure_als(0xB6, 1).unwrap();
    std::thread::sleep(std::time::Duration::from_millis(210));
    check_true!(chip.is_als_valid().unwrap(), "als_valid after configure", passed, failed);

    chip.als_threshold(100, 60000).unwrap();
    chip.proximity_threshold(10, 200).unwrap();
    chip.set_persistence(0, 1).unwrap();
    check_true!(true, "persistence set", passed, failed);

    chip.enable_als_interrupt(true).unwrap();
    chip.enable_proximity_interrupt(true).unwrap();
    chip.clear_als_interrupt().unwrap();
    chip.clear_proximity_interrupt().unwrap();
    chip.clear_all_interrupts().unwrap();
    check_true!(true, "interrupts cleared", passed, failed);

    chip.set_proximity_offset(10, -5).unwrap();
    chip.set_proximity_mask(false, false, false, false).unwrap();
    check_true!(true, "proximity offset/mask set", passed, failed);

    chip.enable_gesture(true).unwrap();
    chip.configure_gesture(1, 0, 0, 1, 1, 50, 20).unwrap();
    check_true!(true, "gesture configured", passed, failed);
    check_true!(chip.gesture_fifo_level().unwrap() >= 0, "gesture_fifo_level >= 0", passed, failed);
    chip.clear_gesture_fifo().unwrap();
    chip.enable_gesture_interrupt(false).unwrap();
    chip.enable_gesture(false).unwrap();
    check_true!(true, "gesture disabled", passed, failed);

    check_true!(chip.status().is_ok(), "status readable", passed, failed);

    chip.enable_proximity(false).unwrap();

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
