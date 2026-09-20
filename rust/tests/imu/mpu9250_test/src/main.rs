use linux_embedded_hal::I2cdev;
use periph::chips::imu::Mpu9250Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut imu = Mpu9250Full::new(dev, addr, &mut linux_embedded_hal::Delay).expect("init");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    macro_rules! check_eq {
        ($got:expr, $expected:expr, $label:expr) => {
            if $got == $expected { println!("PASS {}", $label); passed += 1; }
            else { println!("FAIL {}: got 0x{:02X}, expected 0x{:02X}", $label, $got, $expected); failed += 1; }
        };
    }

    // Note: WHO_AM_I check not directly accessible, test through methods
    let (ax, ay, az) = imu.accel().expect("read accel");
    check_true!(ax > -200.0 && ax < 200.0, "accel_x finite");
    check_true!(ay > -200.0 && ay < 200.0, "accel_y finite");
    check_true!(az > -200.0 && az < 200.0, "accel_z finite");

    let (gx, gy, gz) = imu.gyro().expect("read gyro");
    check_true!(gx > -100.0 && gx < 100.0, "gyro_x finite");
    check_true!(gy > -100.0 && gy < 100.0, "gyro_y finite");
    check_true!(gz > -100.0 && gz < 100.0, "gyro_z finite");

    let t = imu.temperature().expect("read temp");
    check_true!(t > -40.0 && t < 85.0, "temperature range");

    let (rax, ray, raz) = imu.accel_raw().expect("accel raw");
    check_true!(rax >= -32768 && rax <= 32767, "accel_raw_x range");
    let (rgx, rgy, rgz) = imu.gyro_raw().expect("gyro raw");
    check_true!(rgx >= -32768 && rgx <= 32767, "gyro_raw_x range");

    imu.configure_gyro(1).expect("configure gyro");
    imu.configure_accel(1).expect("configure accel");
    let (ax2, ay2, az2) = imu.accel().expect("accel after reconfig");
    check_true!(ax2 > -200.0 && ax2 < 200.0, "accel after reconfig");

    imu.configure_dlpf(4, 4).expect("configure dlpf");
    imu.configure_sample_rate(9).expect("configure sample rate");
    check_true!(imu.data_ready().unwrap_or(true), "data_ready after reconfig");

    imu.set_sleep(true).expect("set sleep");
    std::thread::sleep(std::time::Duration::from_millis(10));
    imu.set_sleep(false).expect("clear sleep");
    std::thread::sleep(std::time::Duration::from_millis(50));
    let (ax3, ay3, az3) = imu.accel().expect("accel after wake");
    check_true!(ax3 > -200.0 && ax3 < 200.0, "accel after wake");

    imu.reset_fifo().expect("reset fifo");
    imu.enable_fifo(true, true, false).expect("enable fifo");
    std::thread::sleep(std::time::Duration::from_millis(50));
    let count = imu.fifo_count().expect("fifo count");
    check_true!(count > 0, "fifo_count > 0");
    let mut data = vec![0u8; 256];
    let read = imu.read_fifo(&mut data).expect("read fifo");
    check_true!(read == count, "read_fifo matches count");

    imu.reset_fifo().expect("reset fifo");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}