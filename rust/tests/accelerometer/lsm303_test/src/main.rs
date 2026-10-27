use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::lsm303::{FIFO_BYPASS, FIFO_FIFO, SOURCE_DATA_READY};
use periph::chips::accelerometer::Lsm303Full;
use periph::connection::Connection;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lsm303Full::new(Connection::new(dev), 0x19, 0x1E, Delay).expect("init LSM303");

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check {
        ($label:expr, $cond:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let id = chip.read_mag_identity().expect("read_mag_identity");            // Read magnetometer identity, () -> [u8; 3]
    check!("mag_identity", id == [0x48, 0x34, 0x33]);

    let (ax, ay, az) = chip.read_acceleration().expect("read_acceleration");  // Read 3-axis acceleration, () -> (g, g, g)
    check!("read_acceleration_floats", ax.is_finite() && ay.is_finite() && az.is_finite());
    check!("acceleration_plausible", ax.abs() < 18.0 && ay.abs() < 18.0 && az.abs() < 18.0);

    let (mx, my, mz) = chip.read_magnetic().expect("read_magnetic");          // Read 3-axis magnetic field, () -> (µT, µT, µT)
    check!("read_magnetic_floats", mx.is_finite() && my.is_finite() && mz.is_finite());
    check!("magnetic_plausible", mx.abs() < 1000.0 && my.abs() < 1000.0 && mz.abs() < 1000.0);

    let heading = chip.heading(true).expect("heading");                      // Compute compass heading, (tilt_compensate) -> deg
    check!("heading_range", (0.0..=360.0).contains(&heading));

    chip.set_range(4).expect("set_range");                                   // Set accelerometer range, (range_g) -> g
    let (ax, _, _) = chip.read_acceleration().expect("read_acceleration");    // Read 3-axis acceleration, () -> (g, g, g)
    check!("read_after_set_range_4g", ax.is_finite());

    let raw = chip.read_acceleration_raw().expect("read_acceleration_raw");  // Read raw acceleration counts, () -> (LSB, LSB, LSB)
    check!("read_acceleration_raw_ints", raw.0 >= -2048 && raw.0 <= 2047);

    chip.set_fifo(FIFO_FIFO, 8, false).expect("set_fifo");                   // Configure FIFO, (mode, watermark, trigger_int2)
    let level = chip.fifo_level().expect("fifo_level");                      // Read FIFO level, () -> u8
    check!("fifo_level_in_range", level <= 32);
    let samples = chip.read_fifo(None).expect("read_fifo");                  // Drain FIFO, (count) -> Vec
    check!("read_fifo_count", samples.len() <= 32);
    chip.set_fifo(FIFO_BYPASS, 0, false).expect("set_fifo");                 // Configure FIFO, (mode, watermark, trigger_int2)

    chip.set_high_pass(0, 0, false, false, false, false).expect("set_high_pass"); // Configure high-pass filter, (...)
    let reference = chip.reset_high_pass().expect("reset_high_pass");         // Reset high-pass filter, () -> u8
    check!("reset_high_pass_stable", chip.reset_high_pass().expect("reset_high_pass") == reference);

    chip.set_mag_range(470).expect("set_mag_range");                         // Set magnetometer range, (range_ut) -> µT
    chip.set_mag_data_rate(30.0).expect("set_mag_data_rate");                // Set magnetometer data rate, (rate_hz) -> Hz
    let (mx, _, _) = chip.read_magnetic().expect("read_magnetic");            // Read 3-axis magnetic field, () -> (µT, µT, µT)
    check!("read_after_set_mag_range_470ut", mx.is_finite());

    chip.enable_temperature(true).expect("enable_temperature");              // Enable temperature sensor, (enabled)
    let temp = chip.read_temperature_delta().expect("read_temperature_delta"); // Read relative temperature, () -> °C
    check!("temperature_plausible", temp.abs() < 100.0);

    chip.enable_interrupt(SOURCE_DATA_READY, 1).expect("enable_interrupt");  // Enable interrupt source, (source, pin)
    chip.poll_interrupt().expect("poll_interrupt");                          // Poll interrupt status, () -> u16
    chip.disable_interrupt(SOURCE_DATA_READY, 1).expect("disable_interrupt"); // Disable interrupt source, (source, pin)

    chip.reboot().expect("reboot");                                          // Reboot accelerometer memory, () -> None
    let (ax, _, _) = chip.read_acceleration().expect("read_acceleration");   // Read 3-axis acceleration, () -> (g, g, g)
    check!("read_after_reboot", ax.is_finite());

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
