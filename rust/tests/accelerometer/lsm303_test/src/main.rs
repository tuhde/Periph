use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{Lsm303Full, SOURCE_CLICK, SOURCE_IA1, SOURCE_MAG_DATA_READY};
use periph::connection::connection::Connection;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let conn = Connection::new(dev);
    let mut delay = Delay;
    let mut chip = Lsm303Full::new(conn, &mut delay).expect("init LSM303");

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    // The two slaves (accelerometer 0x19, magnetometer 0x1E) are both
    // addressed through the single connection.
    let identity = chip.read_mag_identity().expect("read_mag_identity");
    check_true!(identity == [0x48, 0x34, 0x33], "mag_identity_H43");

    let (x, y, z) = chip.read_acceleration().expect("read_acceleration");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "accel_returns_floats");
    let mag = (x * x + y * y + z * z).sqrt();
    check_true!(mag >= 0.5 && mag <= 1.5, "accel_magnitude_near_1g");

    let (mx, my, mz) = chip.read_magnetic().expect("read_magnetic");
    check_true!(mx.is_finite() && my.is_finite() && mz.is_finite(), "mag_returns_finite");
    let field = (mx * mx + my * my + mz * mz).sqrt();
    check_true!(field < 810.0 * 2.0, "mag_field_in_range");

    check_true!(chip.new_data_available().expect("new_data_available"), "accel_data_ready");
    let (drdy, _lock) = chip.read_mag_status().expect("read_mag_status");
    check_true!(drdy, "mag_data_ready");

    // Configuration round-trip at the default ±2 g / 100 Hz.
    chip.set_range(4).expect("set_range");
    let (x4, _, _) = chip.read_acceleration().expect("read_acceleration_4g");
    check_true!(x4.is_finite(), "accel_after_set_range_4g");
    chip.set_range(2).expect("set_range_back");

    chip.set_data_rate(50).expect("set_data_rate");
    chip.set_mode(0).expect("set_mode_low_power");
    chip.set_mode(2).expect("set_mode_high_resolution");
    check_true!(true, "mode_and_rate_config_accepted");

    // Threshold encodings at ±2 g: 0.5 g -> round(0.5*128/2) = 32.
    chip.set_click(0, 4, 0.5, 10.0, 20.0, 50.0).expect("set_click");
    chip.set_free_fall(1, 0.35, 30.0).expect("set_free_fall");
    chip.set_latch(1, true).expect("set_latch");
    chip.enable_interrupt(SOURCE_CLICK, 1).expect("enable_click_int");
    chip.enable_interrupt(SOURCE_IA1, 1).expect("enable_ia1_int");
    check_true!(true, "interrupt_config_accepted");

    let status = chip.poll_interrupt().expect("poll_interrupt");
    check_true!(status & SOURCE_MAG_DATA_READY != 0, "poll_reports_mag_drdy");

    chip.set_fifo(2, 16, false).expect("set_fifo");
    let level = chip.fifo_level().expect("fifo_level");
    check_true!(level <= 32, "fifo_level_in_range");
    let samples = chip.read_fifo(None).expect("read_fifo");
    check_true!(samples.len() as u8 <= 32, "fifo_drain_bounded");
    chip.set_fifo(0, 0, false).expect("fifo_bypass");

    chip.set_mag_range(810).expect("set_mag_range");
    chip.set_mag_data_rate(30.0).expect("set_mag_data_rate");
    check_true!(true, "mag_config_accepted");

    chip.enable_temperature(true).expect("enable_temperature");
    let temperature = chip.read_temperature_delta().expect("read_temperature_delta");
    check_true!(temperature.is_finite(), "temperature_finite");

    let heading = chip.heading(true).expect("heading");
    check_true!((0.0..=360.0).contains(&heading), "heading_in_0_360");

    let mut delay2 = Delay;
    chip.reboot(&mut delay2).expect("reboot");
    check_true!(true, "reboot_accepted");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
