use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::imu::Mpu9250Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut imu = Mpu9250Full::new(dev, addr, &mut delay).expect("init");

    let (ax, ay, az) = imu.accel().expect("read accel");
    let (gx, gy, gz) = imu.gyro().expect("read gyro");
    println!("accel: {:.2} {:.2} {:.2}  gyro: {:.2} {:.2} {:.2}", ax, ay, az, gx, gy, gz);

    imu.configure_gyro(1).expect("configure gyro");
    imu.configure_accel(1).expect("configure accel");
    imu.configure_dlpf(3, 3).expect("configure dlpf");
    imu.configure_sample_rate(4).expect("configure sample rate");

    let t = imu.temperature().expect("read temp");
    println!("temperature: {:.2} °C", t);

    imu.enable_mag(16, 6, &mut delay).expect("enable mag");
    let (mx, my, mz) = imu.mag().expect("read mag");
    println!("mag: {:.2} {:.2} {:.2} µT", mx, my, mz);

    let (rax, ray, raz) = imu.accel_raw().expect("accel raw");
    let (rgx, rgy, rgz) = imu.gyro_raw().expect("gyro raw");
    let (rmx, rmy, rmz) = imu.mag_raw().expect("mag raw");
    println!("accel_raw: {} {} {}", rax, ray, raz);
    println!("gyro_raw: {} {} {}", rgx, rgy, rgz);
    println!("mag_raw: {} {} {}", rmx, rmy, rmz);

    let ready = imu.data_ready().expect("data ready");
    println!("data_ready: {}", ready);

    imu.set_sleep(true).expect("set sleep");
    std::thread::sleep(std::time::Duration::from_millis(10));
    imu.set_sleep(false).expect("clear sleep");
    std::thread::sleep(std::time::Duration::from_millis(50));
    let (ax3, ay3, az3) = imu.accel().expect("accel after wake");
    println!("accel after wake: {:.2} {:.2} {:.2}", ax3, ay3, az3);

    imu.reset_fifo().expect("reset fifo");
    imu.enable_fifo(true, true, false).expect("enable fifo");
    std::thread::sleep(std::time::Duration::from_millis(50));
    let count = imu.fifo_count().expect("fifo count");
    println!("fifo_count: {}", count);
    let mut data = vec![0u8; 256];
    let read = imu.read_fifo(&mut data).expect("read fifo");
    println!("read_fifo: {} bytes", read);
    imu.reset_fifo().expect("reset fifo");
}