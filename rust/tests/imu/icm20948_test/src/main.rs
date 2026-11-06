use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::imu::Icm20948Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut imu = Icm20948Full::new(dev, addr, &mut delay).expect("init");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (ax, ay, az) = imu.accel().expect("read accel");
    check_true!(ax > -200.0 && ax < 200.0 && ay > -200.0 && ay < 200.0 && az > -200.0 && az < 200.0, "accel finite");
    let g_mag = (ax * ax + ay * ay + az * az).sqrt();
    check_true!(g_mag > 5.0 && g_mag < 15.0, "gravity magnitude");

    let (gx, gy, gz) = imu.gyro().expect("read gyro");
    check_true!(gx > -100.0 && gx < 100.0 && gy > -100.0 && gy < 100.0 && gz > -100.0 && gz < 100.0, "gyro finite");

    let t = imu.temperature().expect("read temp");
    check_true!(t > -40.0 && t < 85.0, "temperature range");

    let (_, _, tc) = imu.read_all().expect("read_all");
    check_true!(tc > -40.0 && tc < 85.0, "read_all temp range");

    let (rax, ray, raz) = imu.accel_raw().expect("accel raw");
    let raw_mag = ((rax as f32) * (rax as f32) + (ray as f32) * (ray as f32) + (raz as f32) * (raz as f32)).sqrt();
    check_true!(raw_mag > 8000.0 && raw_mag < 24000.0, "accel_raw gravity");
    let _ = imu.gyro_raw().expect("gyro raw");

    imu.configure_gyro(1).expect("configure gyro");
    imu.configure_accel(1).expect("configure accel");
    let (ax2, _, _) = imu.accel().expect("accel after reconfig");
    check_true!(ax2 > -200.0 && ax2 < 200.0, "accel after reconfig");

    imu.configure_filters(4, 4, true, true).expect("configure filters");
    imu.configure_sample_rate(9, 9).expect("configure sample rate");
    let (ox, oy, oz) = imu.get_accel_offset().expect("accel offset");
    check_true!(ox >= -16384 && oy >= -16384 && oz >= -16384, "accel offset readable");

    imu.enable_mag(&mut delay).expect("enable mag");
    std::thread::sleep(std::time::Duration::from_millis(150));
    let (mx, my, mz) = imu.mag().expect("read mag");
    let m_mag = (mx * mx + my * my + mz * mz).sqrt();
    check_true!(m_mag > 5.0 && m_mag < 150.0, "mag magnitude");
    check_true!(imu.aux_read_byte(0x0C, 0x01, &mut delay).expect("aux read") == 0x09, "aux_read WIA2");

    imu.set_sleep(true).expect("set sleep");
    std::thread::sleep(std::time::Duration::from_millis(10));
    imu.set_sleep(false).expect("clear sleep");
    std::thread::sleep(std::time::Duration::from_millis(50));
    let (ax3, _, _) = imu.accel().expect("accel after wake");
    check_true!(ax3 > -200.0 && ax3 < 200.0, "accel after wake");

    imu.enable_fifo(true, true, false).expect("enable fifo");
    std::thread::sleep(std::time::Duration::from_millis(50));
    let count = imu.fifo_count().expect("fifo count");
    check_true!(count > 0, "fifo_count > 0");
    let mut data = [0u8; 256];
    let read = imu.read_fifo(&mut data).expect("read fifo");
    check_true!(read > 0, "read_fifo returns bytes");
    imu.reset_fifo().expect("reset fifo");

    imu.set_wake_on_motion(200.0, true).expect("wake on motion");
    let status = imu.poll_interrupt().expect("poll interrupt");
    check_true!(status <= 0x1F, "poll_interrupt callable");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
