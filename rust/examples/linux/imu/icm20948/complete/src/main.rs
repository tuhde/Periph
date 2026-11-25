use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::imu::icm20948::{Icm20948Full, SOURCE_DATA_READY};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    // Create ICM20948 driver, (i2c, addr=0x68, delay) → Result
    let mut imu = Icm20948Full::new(dev, addr, &mut delay).expect("init");

    // Read 3-axis acceleration, () → Result<(f32, f32, f32)> m/s²
    let (ax, ay, az) = imu.accel().expect("read accel");
    // converts raw accel registers to m/s² (16384 LSB/g at ±2g)
    // Read 3-axis angular rate, () → Result<(f32, f32, f32)> rad/s
    let (gx, gy, gz) = imu.gyro().expect("read gyro");
    // converts raw gyro registers to rad/s (131 LSB/(°/s) at ±250dps)
    // Read one coherent sample, () → Result<((f32, f32, f32), (f32, f32, f32), f32)> m/s², rad/s, °C
    let (accel, gyro, temp) = imu.read_all().expect("read_all");
    // burst-reads registers 0x2D…0x3A so all three belong to one sample
    // Read die temperature, () → Result<f32> °C
    let t = imu.temperature().expect("read temp");
    // converts raw temp register: raw/333.87 + 21.0
    // Read raw accel values, () → Result<(i16, i16, i16)>
    let (rax, ray, raz) = imu.accel_raw().expect("accel raw");
    // returns signed 16-bit accelerometer counts
    // Read raw gyro values, () → Result<(i16, i16, i16)>
    let (rgx, rgy, rgz) = imu.gyro_raw().expect("gyro raw");
    // returns signed 16-bit gyroscope counts

    // Configure gyro range, (full_scale) → Result
    imu.configure_gyro(1).expect("configure gyro");
    // sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    // Configure accel range, (full_scale) → Result
    imu.configure_accel(1).expect("configure accel");
    // sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    // Configure low-pass filters, (gyro_dlpf, accel_dlpf, gyro_filter, accel_filter) → Result
    imu.configure_filters(3, 3, true, true).expect("configure filters");
    // sets *_DLPFCFG and *_FCHOICE; filter false bypasses the filter
    // Configure sample rate, (gyro_div, accel_div) → Result
    imu.configure_sample_rate(10, 10).expect("configure sample rate");
    // sets the dividers; ODR = 1125 Hz / (1 + div)
    // Power sensors, (accel, gyro) → Result
    imu.set_sensors(true, true).expect("set sensors");
    // writes PWR_MGMT_2 DISABLE_ACCEL / DISABLE_GYRO
    // Configure duty-cycled mode, (accel, gyro, accel_avg, gyro_avg) → Result
    imu.set_low_power(false, false, 0, 0).expect("set low power");
    // writes LP_CONFIG and averaging; LP_EN is set last
    // Set gyro offsets, (x, y, z) rad/s → Result
    imu.set_gyro_offset(0.0, 0.0, 0.0).expect("set gyro offset");
    // writes the 16-bit offset-cancellation registers (0.0305 dps/LSB)
    // Read accel offset codes, () → Result<(i16, i16, i16)>
    let (ox, oy, oz) = imu.get_accel_offset().expect("get accel offset");
    // reads the factory-trimmed 15-bit offsets (0.98 mg/LSB)
    // Write accel offset codes, (x, y, z) → Result
    imu.set_accel_offset(ox, oy, oz).expect("set accel offset");
    // writes the 15-bit offsets back unchanged

    // Enable magnetometer, (delay) → Result
    imu.enable_mag(&mut delay).expect("enable mag");
    // starts the I²C master and reads the AK09916 block continuously
    // Read 3-axis magnetic field, () → Result<(f32, f32, f32)> µT
    let (mx, my, mz) = imu.mag().expect("read mag");
    // converts raw counts at 0.15 µT/LSB; Err(MagOverflow) on HOFL
    // Read raw mag values, () → Result<(i16, i16, i16)>
    let (rmx, rmy, rmz) = imu.mag_raw().expect("mag raw");
    // returns signed 16-bit magnetometer counts
    // Check magnetometer data ready, () → Result<bool>
    let mag_ready = imu.mag_data_ready().expect("mag ready");
    // reads ST1.DRDY from the shadowed block
    // Set magnetometer mode, (mode, delay) → Result
    imu.mag_set_mode(8, &mut delay).expect("mag mode");
    // writes CNTL2: 0=off, 1=single, 2/4/6/8=continuous 1-4
    // Read auxiliary byte, (address, register, delay) → Result<u8>
    let who = imu.aux_read_byte(0x0C, 0x01, &mut delay).expect("aux read");
    // reads AK09916 WIA2 (0x09) through I²C-master slave 4
    // Write auxiliary byte, (address, register, value, delay) → Result
    imu.aux_write_byte(0x0C, 0x31, 0x08, &mut delay).expect("aux write");
    // writes AK09916 CNTL2 through slave 4

    // Check data ready flag, () → Result<bool>
    let ready = imu.data_ready().expect("data ready");
    // reads INT_STATUS_1.RAW_DATA_0_RDY_INT

    // Enable FIFO sources, (accel, gyro, temp) → Result
    imu.enable_fifo(true, true, false).expect("enable fifo");
    // sets FIFO_EN_2 and USER_CTRL.FIFO_EN, then resets the FIFO
    // Select FIFO mode, (snapshot) → Result
    imu.set_fifo_mode(false).expect("fifo mode");
    // false = stream (overwrite oldest), true = snapshot (stop when full)
    std::thread::sleep(std::time::Duration::from_millis(50));
    // Read FIFO byte count, () → Result<u16>
    let count = imu.fifo_count().expect("fifo count");
    // reads FIFO_COUNTH/L: number of bytes available
    let mut data = [0u8; 256];
    // Read FIFO data, (buf) → Result<usize>
    let read = imu.read_fifo(&mut data).expect("read fifo");
    // reads available bytes from FIFO_R_W in chunks
    // Reset FIFO buffer, () → Result
    imu.reset_fifo().expect("reset fifo");
    // asserts and de-asserts FIFO_RST

    // Arm wake-on-motion, (threshold_mg, enabled) → Result
    imu.set_wake_on_motion(200.0, true).expect("wake on motion");
    // writes ACCEL_WOM_THR (4 mg/LSB) and ACCEL_INTEL_CTRL
    // Configure INT1 pin, (active_low, open_drain, latch, any_read_clears) → Result
    imu.set_interrupt_pin(false, false, true, false).expect("interrupt pin");
    // writes INT_PIN_CFG
    // Enable interrupt source, (source) → Result
    imu.enable_interrupt(SOURCE_DATA_READY).expect("enable interrupt");
    // sets the source's enable bit in INT_ENABLE*
    // Read interrupt status, () → Result<u8>
    let status = imu.poll_interrupt().expect("poll interrupt");
    // reads INT_STATUS, _1, _2 (clearing them) into SOURCE_* bits
    // Disable interrupt source, (source) → Result
    imu.disable_interrupt(SOURCE_DATA_READY).expect("disable interrupt");
    // clears the source's enable bit

    // Run self-test, (delay) → Result<([f32; 3], [f32; 3])>
    let (st_accel, st_gyro) = imu.self_test(&mut delay).expect("self test");
    // raw response (enabled minus disabled) per axis; no pass/fail
    // Read PLL time-base correction, () → Result<f32> %
    let tbc = imu.read_timebase_correction().expect("timebase");
    // reads TIMEBASE_CORRECTION_PLL (0.079 %/LSB, signed)

    // Enter sleep mode, (sleep) → Result
    imu.set_sleep(true).expect("set sleep");
    // sets SLEEP bit in PWR_MGMT_1
    std::thread::sleep(std::time::Duration::from_millis(10));
    // Wake from sleep, (sleep) → Result
    imu.set_sleep(false).expect("clear sleep");
    // clears SLEEP bit in PWR_MGMT_1

    println!("accel: {:.2} {:.2} {:.2}  gyro: {:.2} {:.2} {:.2}", ax, ay, az, gx, gy, gz);
    println!("temperature: {:.2} °C  mag: {:.1} {:.1} {:.1} µT  ready: {}", t, mx, my, mz, ready);
    let _ = (accel, gyro, temp, rax, ray, raz, rgx, rgy, rgz, rmx, rmy, rmz, mag_ready, who, count, read, status, st_accel, st_gyro, tbc);
    // Reset the device, (delay) → Result
    imu.reset(&mut delay).expect("reset");
    // DEVICE_RESET and wait; create a new driver afterwards
}
