use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::imu::Mpu6050Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Mpu6050Full::new(dev, addr, &mut delay).expect("init MPU6050"); // Create MPU6050 driver, (i2c, addr, delay) → Result

    let (ax, ay, az) = chip.accel().expect("accel");     // Read 3-axis acceleration, () → (f32, f32, f32) m/s²
                                                         // converts raw accel register to m/s² (16384 LSB/g at ±2g)
    let (gx, gy, gz) = chip.gyro().expect("gyro");       // Read 3-axis angular rate, () → (f32, f32, f32) rad/s
                                                         // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)
    println!("accel: {:.2} {:.2} {:.2}", ax, ay, az);
    println!("gyro:  {:.2} {:.2} {:.2}", gx, gy, gz);

    chip.configure_gyro(1).expect("configure_gyro");      // Configure gyro range, (full_scale=0) → Result
                                                         // sets FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    chip.configure_accel(1).expect("configure_accel");    // Configure accel range, (full_scale=0) → Result
                                                         // sets AFS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    chip.configure_dlpf(3).expect("configure_dlpf");      // Configure DLPF bandwidth, (dlpf=3) → Result
                                                         // sets DLPF_CFG: 0=260Hz … 6=5Hz (gyro/accel BW)
    chip.configure_sample_rate(4).expect("configure_sample_rate"); // Configure sample rate, (divider=4) → Result
                                                         // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

    let t = chip.temperature().expect("temperature");     // Read die temperature, () → f32 °C
    println!("temp:  {:.1} C", t);                       // converts raw temp register: raw/340 + 36.53

    let (rax, ray, raz) = chip.accel_raw().expect("accel_raw"); // Read raw accel values, () → (i16, i16, i16)
    let (rgx, rgy, rgz) = chip.gyro_raw().expect("gyro_raw");   // Read raw gyro values, () → (i16, i16, i16)
    println!("raw accel: {} {} {}", rax, ray, raz);      // returns raw 16-bit signed accelerometer register values
    println!("raw gyro:  {} {} {}", rgx, rgy, rgz);      // returns raw 16-bit signed gyroscope register values

    let ready = chip.data_ready().expect("data_ready");   // Check data ready flag, () → bool
    println!("data_ready: {}", ready);                    // reads DATA_RDY_INT bit from INT_STATUS register

    chip.set_sleep(true).expect("set_sleep");             // Enter sleep mode, (sleep=true) → Result
                                                         // sets SLEEP bit in PWR_MGMT_1
    sleep(Duration::from_millis(10));
    chip.set_sleep(false).expect("set_sleep");            // Wake from sleep, (sleep=true) → Result
                                                         // clears SLEEP bit in PWR_MGMT_1
    sleep(Duration::from_millis(50));

    chip.set_standby(true, false, false, false, false, false).expect("set_standby"); // Set axes standby, (xa, ya, za, xg, yg, zg) → Result
    chip.set_standby(false, false, false, false, false, false).expect("set_standby"); // Clear all standby, (xa=false, ...) → Result

    chip.reset_fifo().expect("reset_fifo");               // Reset FIFO buffer, () → Result
    chip.enable_fifo(true, true, false).expect("enable_fifo"); // Enable FIFO sources, (gyro=true, accel=true, temp=false) → Result
    sleep(Duration::from_millis(50));
    let count = chip.fifo_count().expect("fifo_count");   // Read FIFO byte count, () → u16
    println!("fifo_count: {}", count);                    // reads FIFO_COUNTH/L: number of bytes available
    let mut buf = [0u8; 256];
    let n = chip.read_fifo(&mut buf).expect("read_fifo"); // Read FIFO data, (buf) → u16
    println!("fifo read: {} bytes", n);                   // reads all available bytes from FIFO_R_W register
    chip.reset_fifo().expect("reset_fifo");               // Reset FIFO buffer, () → Result
}
