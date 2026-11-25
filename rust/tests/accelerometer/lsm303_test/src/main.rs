use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{
    Lsm303Full, MODE_HIGH_RESOLUTION, FIFO_STREAM, MAG_MODE_CONTINUOUS, SOURCE_CLICK,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lsm303Full::new(dev).expect("init LSM303");

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    // --- Minimal: acceleration ---
    let (x, y, z) = chip.read_acceleration().expect("read acceleration");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_acceleration_returns_floats");
    let magnitude = (x * x + y * y + z * z).sqrt();
    check_true!(magnitude >= 0.5 && magnitude <= 1.5, "acceleration_magnitude_near_1g");

    // --- Minimal: magnetic field ---
    let (mx, my, mz) = chip.read_magnetic().expect("read magnetic");
    check_true!(mx.is_finite() && my.is_finite() && mz.is_finite(), "read_magnetic_returns_floats");

    // --- Full: configuration ---
    check_true!(chip.set_range(4).is_ok(), "set_range_ok");
    check_true!(chip.set_data_rate(50).is_ok(), "set_data_rate_ok");
    check_true!(chip.set_mode(MODE_HIGH_RESOLUTION).is_ok(), "set_mode_ok");
    check_true!(chip.set_axes(true, true, true).is_ok(), "set_axes_ok");
    check_true!(chip.new_data_available().is_ok(), "new_data_available_ok");
    check_true!(chip.read_status().is_ok(), "read_status_ok");

    // --- Full: high-pass filter and FIFO ---
    check_true!(chip.set_high_pass(0, 0, false, true, false, false).is_ok(), "set_high_pass_ok");
    check_true!(chip.reset_high_pass().is_ok(), "reset_high_pass_ok");
    check_true!(chip.set_reference(0).is_ok(), "set_reference_ok");
    check_true!(chip.set_fifo(FIFO_STREAM, 16, false).is_ok(), "set_fifo_ok");
    check_true!(chip.fifo_level().is_ok(), "fifo_level_ok");
    check_true!(chip.read_fifo_status().is_ok(), "read_fifo_status_ok");

    // --- Full: interrupt generators ---
    check_true!(chip.set_motion_interrupt(1, 0x02, 0.5, 10.0, false).is_ok(), "set_motion_interrupt_ok");
    check_true!(chip.set_free_fall(1, 0.35, 30.0).is_ok(), "set_free_fall_ok");
    check_true!(chip.set_orientation(1, false, false).is_ok(), "set_orientation_ok");
    check_true!(chip.set_latch(1, true).is_ok(), "set_latch_ok");
    check_true!(chip.read_interrupt_source(1).is_ok(), "read_interrupt_source_ok");
    check_true!(chip.set_click(0, 0x04, 0.8, 20.0, 40.0, 200.0).is_ok(), "set_click_ok");
    check_true!(chip.read_click_source().is_ok(), "read_click_source_ok");
    check_true!(chip.set_interrupt_polarity(false).is_ok(), "set_interrupt_polarity_ok");
    check_true!(chip.enable_interrupt(SOURCE_CLICK, 1).is_ok(), "enable_interrupt_ok");
    check_true!(chip.disable_interrupt(SOURCE_CLICK, 1).is_ok(), "disable_interrupt_ok");

    // --- Full: magnetometer ---
    check_true!(chip.set_mag_range(250).is_ok(), "set_mag_range_ok");
    check_true!(chip.set_mag_data_rate(30.0).is_ok(), "set_mag_data_rate_ok");
    check_true!(chip.set_mag_mode(MAG_MODE_CONTINUOUS).is_ok(), "set_mag_mode_ok");
    check_true!(chip.read_magnetic_raw().is_ok(), "read_magnetic_raw_ok");
    check_true!(chip.read_mag_status().is_ok(), "read_mag_status_ok");
    let identity = chip.read_mag_identity().expect("read mag identity");
    check_true!(identity == [0x48, 0x34, 0x33], "mag_identity_matches");
    check_true!(chip.enable_temperature(true).is_ok(), "enable_temperature_ok");
    check_true!(chip.read_temperature_delta().is_ok(), "read_temperature_delta_ok");

    // --- Full: compass heading ---
    let heading = chip.heading(false).expect("heading");
    check_true!(heading >= 0.0 && heading < 360.0, "heading_in_range");

    // --- Full: interrupt status ---
    check_true!(chip.poll_interrupt().is_ok(), "poll_interrupt_ok");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
