use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::imu::Qmi8658aFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x6B);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut imu = Qmi8658aFull::new(dev, addr, &mut delay).expect("init");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (ax, ay, az) = imu.accel().expect("read accel");
    let g_mag = (ax * ax + ay * ay + az * az).sqrt();
    check_true!(g_mag > 7.0 && g_mag < 12.6, "gravity magnitude");

    let (gx, gy, gz) = imu.gyro().expect("read gyro");
    check_true!(gx.abs() < 10.0 && gy.abs() < 10.0 && gz.abs() < 10.0, "gyro finite");

    let (_, _, tc) = imu.read_all().expect("read_all");
    check_true!(tc > -40.0 && tc < 85.0, "read_all temp range");
    let t = imu.temperature().expect("read temp");
    check_true!((t - tc).abs() < 5.0, "temperature matches");

    let (rax, ray, raz) = imu.accel_raw().expect("accel raw");
    let raw_mag = ((rax as f32) * (rax as f32) + (ray as f32) * (ray as f32) + (raz as f32) * (raz as f32)).sqrt();
    check_true!(raw_mag > 8000.0 && raw_mag < 24000.0, "accel_raw gravity");
    let _ = imu.gyro_raw().expect("gyro raw");

    let ts1 = imu.timestamp().expect("timestamp");
    std::thread::sleep(std::time::Duration::from_millis(100));
    check_true!(imu.timestamp().expect("timestamp") != ts1, "timestamp advances");

    imu.configure_accel(1, 5).expect("configure accel");
    imu.configure_gyro(5, 5).expect("configure gyro");
    std::thread::sleep(std::time::Duration::from_millis(200));
    let (bx, by, bz) = imu.accel().expect("accel after reconfig");
    let mag2 = (bx * bx + by * by + bz * bz).sqrt();
    check_true!(mag2 > 7.0 && mag2 < 12.6, "accel after reconfig");
    imu.configure_accel(0, 5).expect("configure accel");
    imu.configure_gyro(4, 5).expect("configure gyro");
    imu.configure_filters(true, 3, true, 3).expect("configure filters");
    std::thread::sleep(std::time::Duration::from_millis(200));

    imu.configure_fifo(2, 1, 8).expect("configure fifo");
    std::thread::sleep(std::time::Duration::from_millis(300));
    check_true!(imu.fifo_count().expect("fifo count") > 0, "fifo_count non-zero");
    let mut fifo = [0u8; 96];
    let got = imu.read_fifo(&mut fifo, &mut delay).expect("read fifo");
    check_true!(got > 0 && got % 12 == 0, "read_fifo returns whole frames");
    imu.reset_fifo(&mut delay).expect("reset fifo");
    imu.configure_fifo(0, 3, 0).expect("bypass");
    std::thread::sleep(std::time::Duration::from_millis(100));

    let (_, firmware, usid) = imu.device_info(&mut delay).expect("device info");
    check_true!(firmware.len() == 3 && usid.len() == 6, "device_info callable");
    imu.set_sensors(true, true, false).expect("sensors");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
