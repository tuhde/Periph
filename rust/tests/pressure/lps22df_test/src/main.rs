use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Lps22dfMinimal, Lps22dfFull};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lps = Lps22dfMinimal::new(dev, addr, false).expect("init LPS22DF");

    let t = lps.temperature().expect("temperature");
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = lps.pressure().expect("pressure");
    check_true!(p >= 26000.0 && p <= 126000.0, "pressure_range", passed, failed);

    drop(lps);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut full = Lps22dfFull::new(dev, addr, false).expect("init LPS22DF Full");
    full.configure(3, 0, true, 1, true).expect("configure");
    check_true!(true, "configure", passed, failed);

    let p2 = full.pressure().expect("pressure after configure");
    check_true!(p2 >= 26000.0 && p2 <= 126000.0, "configure_then_read", passed, failed);

    let alt = full.altitude(101325.0).expect("altitude");
    check_true!(alt >= -500.0 && alt <= 10000.0, "altitude", passed, failed);

    full.set_pressure_threshold(102000.0).expect("threshold");
    full.configure_interrupt(false, false, true, false, true, false, false, false).expect("configure_interrupt");
    let src = full.interrupt_source().expect("interrupt_source");
    check_true!(true, "interrupt_source_readable", passed, failed);
    let _ = src;

    let count = full.fifo_sample_count().expect("fifo_sample_count");
    let mut samples = [0.0f32; 16];
    let n = full.read_fifo(&mut samples).expect("read_fifo");
    check_true!(true, "read_fifo", passed, failed);
    let _ = (count, n);

    check_true!(full.who_am_i().unwrap() == 0xB4, "who_am_i", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}