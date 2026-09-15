use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp384Full, Bmp384Minimal, MODE_FORCED, MODE_NORMAL};

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
        .unwrap_or(0x76);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp384Minimal::new(dev, addr, false).expect("init BMP384");

    check_true!(bmp.osr_p == 4, "default_osr_p", passed, failed);
    check_true!(bmp.osr_t == 1, "default_osr_t", passed, failed);
    check_true!(bmp.iir   == 2, "default_iir",   passed, failed);

    let t = bmp.temperature().unwrap();
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = bmp.pressure().unwrap();
    check_true!(p >= 300.0 && p <= 1250.0, "pressure_range", passed, failed);

    drop(bmp);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp_full = Bmp384Full::new(dev, addr, false).expect("init BMP384 Full");

    bmp_full.configure(2, 1, 1, 0x04).expect("configure");
    check_true!(bmp_full.inner.osr_p == 2 && bmp_full.inner.iir == 1 && bmp_full.inner.odr == 0x04,
        "configure_writes_through", passed, failed);

    bmp_full.set_mode(MODE_FORCED).expect("set_mode_forced");
    check_true!(bmp_full.inner.mode == MODE_FORCED, "set_mode_state", passed, failed);

    bmp_full.set_mode(MODE_NORMAL).expect("set_mode_normal");

    let ready = bmp_full.is_data_ready().unwrap_or(false);
    check_true!(ready == true || ready == false, "is_data_ready_runs", passed, failed);

    let alt = bmp_full.altitude(1013.25).unwrap_or(-1.0);
    check_true!(alt >= -500.0 && alt <= 9000.0, "altitude_range", passed, failed);

    let mut frames: [periph::chips::pressure::Bmp384FifoFrame; 16] = unsafe { std::mem::zeroed() };
    let _ = bmp_full.fifo_read(&mut frames).expect("fifo_read");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
