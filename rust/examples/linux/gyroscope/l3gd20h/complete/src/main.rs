use linux_embedded_hal::I2cdev;
use periph::connection::Connection;
use periph::chips::gyroscope::{L3gd20hFull, ODR_190_HZ, FS_500_DPS, HPM_NORMAL, FIFO_FIFO, POWER_NORMAL};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x6A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let conn = Connection::new(dev);
    let mut gyro = L3gd20hFull::new(conn, addr, false).expect("init");

    gyro.configure(ODR_190_HZ, 0, FS_500_DPS).expect("configure"); // Configure, (odr, bw, full_scale) -> Result

    gyro.configure_hp_filter(HPM_NORMAL, 0).expect("configure_hp_filter"); // Configure HPF
    gyro.enable_hp_filter(true).expect("enable_hp_filter"); // Enable HPF

    gyro.configure_fifo(FIFO_FIFO, 10).expect("configure_fifo"); // Configure FIFO
    gyro.enable_fifo(true).expect("enable_fifo"); // Enable FIFO

    gyro.set_power_mode(POWER_NORMAL).expect("set_power_mode"); // Set power mode

    let who = gyro.inner.read_reg_bytes(0x0F, &mut [0u8; 1]).is_ok();
    println!("WHO_AM_I read successful: {}", who);

    let temp = gyro.temperature().expect("temperature"); // Read temperature, () -> Result<i8>
    println!("Temperature: {}", temp);

    loop {
        if gyro.data_ready().expect("data_ready") { // Check data ready, () -> Result<bool>
            let (x, y, z) = gyro.gyro().expect("gyro"); // Read angular rate, () -> Result<(f32, f32, f32)>
            println!("x={:.3} y={:.3} z={:.3} rad/s", x, y, z);

            let (rx, ry, rz) = gyro.gyro_raw().expect("gyro_raw"); // Read raw, () -> Result<(i16, i16, i16)>
            println!("  raw: x={} y={} z={}", rx, ry, rz);

            let level = gyro.fifo_level().expect("fifo_level"); // FIFO level, () -> Result<u8>
            if level > 0 {
                let samples = gyro.read_fifo().expect("read_fifo"); // Read FIFO, () -> Result<Vec<(f32,f32,f32)>>
                println!("FIFO: {} samples", samples.len());
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
}