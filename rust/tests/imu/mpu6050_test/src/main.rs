use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::imu::Mpu6050Full;

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
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Mpu6050Full::new(dev, addr, &mut delay).expect("init MPU6050");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let (ax, ay, az) = chip.accel().map(|(x, y, z)| (x, y, z)).unwrap_or((0.0, 0.0, 0.0));
    check_true!(ax > -200.0 && ax < 200.0, "accel_x finite", passed, failed);
    check_true!(ay > -200.0 && ay < 200.0, "accel_y finite", passed, failed);
    check_true!(az > -200.0 && az < 200.0, "accel_z finite", passed, failed);

    let (gx, gy, gz) = chip.gyro().map(|(x, y, z)| (x, y, z)).unwrap_or((0.0, 0.0, 0.0));
    check_true!(gx > -100.0 && gx < 100.0, "gyro_x finite", passed, failed);
    check_true!(gy > -100.0 && gy < 100.0, "gyro_y finite", passed, failed);
    check_true!(gz > -100.0 && gz < 100.0, "gyro_z finite", passed, failed);

    let t = chip.temperature().unwrap_or(0.0);
    check_true!(t > -40.0 && t < 85.0, "temperature range", passed, failed);

    let (rax, _, _) = chip.accel_raw().unwrap_or((0, 0, 0));
    check_true!(rax >= -32768 && rax <= 32767, "accel_raw_x range", passed, failed);

    let (rgx, _, _) = chip.gyro_raw().unwrap_or((0, 0, 0));
    check_true!(rgx >= -32768 && rgx <= 32767, "gyro_raw_x range", passed, failed);

    chip.configure_gyro(1).ok();
    chip.configure_accel(1).ok();
    let (ax2, _, _) = chip.accel().unwrap_or((0.0, 0.0, 0.0));
    check_true!(ax2 > -200.0 && ax2 < 200.0, "accel after reconfig", passed, failed);

    chip.configure_dlpf(4).ok();
    chip.configure_sample_rate(9).ok();

    chip.set_sleep(true).ok();
    std::thread::sleep(std::time::Duration::from_millis(10));
    chip.set_sleep(false).ok();
    std::thread::sleep(std::time::Duration::from_millis(50));
    let (ax3, _, _) = chip.accel().unwrap_or((0.0, 0.0, 0.0));
    check_true!(ax3 > -200.0 && ax3 < 200.0, "accel after wake", passed, failed);

    chip.set_standby(true, false, false, false, false, false).ok();
    chip.set_standby(false, false, false, false, false, false).ok();

    chip.reset_fifo().ok();
    chip.enable_fifo(true, true, false).ok();
    std::thread::sleep(std::time::Duration::from_millis(50));
    let count = chip.fifo_count().unwrap_or(0);
    check_true!(count > 0, "fifo_count > 0", passed, failed);
    let mut buf = [0u8; 256];
    let n = chip.read_fifo(&mut buf).unwrap_or(0);
    check_true!(n == count, "read_fifo matches count", passed, failed);

    chip.reset_fifo().ok();

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
