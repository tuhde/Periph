use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{
    Fxos8700Full, Fxos8700Minimal, Fxos8700PulseConfig as PulseConfig, FXOS8700_SOURCE_DATA_READY,
};
use periph::connection::Connection;
use std::time::Duration;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn wait(ms: u64) {
    std::thread::sleep(Duration::from_millis(ms));
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1E);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Minimal driver --------------------------------------------------
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut minimal = Fxos8700Minimal::new(Connection::new(dev), addr, Delay).expect("init FXOS8700");
    let (x, y, z) = minimal.read_accel().expect("read_accel");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "minimal_read_accel", passed, failed);
    let (x, y, z) = minimal.read_mag().expect("read_mag");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "minimal_read_mag", passed, failed);

    // --- Full driver -----------------------------------------------------
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Fxos8700Full::new(Connection::new(dev), addr, Delay).expect("init FXOS8700 Full");

    let (ax, ay, az) = chip.read_accel().expect("read_accel");
    check_true!(ax.is_finite() && ay.is_finite() && az.is_finite(), "read_accel_returns_floats", passed, failed);
    let magnitude = (ax * ax + ay * ay + az * az).sqrt();
    check_true!(magnitude >= 0.7 && magnitude <= 1.3, "accel_magnitude_near_1g", passed, failed);
    let (mx, my, mz) = chip.read_mag().expect("read_mag");
    check_true!((mx).abs() <= 1200.0 && (my).abs() <= 1200.0 && (mz).abs() <= 1200.0, "mag_in_range", passed, failed);

    let hybrid = chip.read_hybrid().expect("read_hybrid");
    check_true!(hybrid.is_some(), "read_hybrid_ok", passed, failed);
    let (rx, ry, rz) = chip.read_raw_accel().expect("read_raw_accel");
    check_true!((-8192..=8191).contains(&rx) && (-8192..=8191).contains(&ry) && (-8192..=8191).contains(&rz),
                "raw_accel_in_14bit_range", passed, failed);
    let _ = chip.read_raw_mag().expect("read_raw_mag");
    let temperature = chip.read_temperature().expect("read_temperature");
    check_true!(matches!(temperature, Some(t) if t > -100.0 && t < 150.0), "temperature_plausible", passed, failed);

    chip.set_range(4).expect("set_range");
    chip.set_data_rate(100.0).expect("set_data_rate");
    wait(200);
    let (_, _, az) = chip.read_accel().expect("read_accel");
    check_true!((az).abs() <= 4.5, "read_after_set_range_4g", passed, failed);
    chip.set_range(2).expect("set_range");
    chip.set_data_rate(200.0).expect("set_data_rate");
    chip.set_accel_oversampling(2, None).expect("set_accel_oversampling");
    chip.set_accel_oversampling(0, None).expect("set_accel_oversampling");
    chip.set_mag_oversampling(7, None).expect("set_mag_oversampling");
    check_true!(chip.read_system_mode().expect("read_system_mode") == 1, "system_mode_wake", passed, failed);
    wait(200);
    let _ = chip.new_accel_data().expect("new_accel_data");
    let _ = chip.new_mag_data().expect("new_mag_data");

    chip.set_accel_offset(0.02, -0.02, 0.0).expect("set_accel_offset");
    let (ox, oy, _) = chip.get_accel_offset().expect("get_accel_offset");
    check_true!((ox - 0.02).abs() < 0.003 && (oy + 0.02).abs() < 0.003, "accel_offset_roundtrip", passed, failed);
    chip.set_accel_offset(0.0, 0.0, 0.0).expect("set_accel_offset");
    chip.set_mag_offset(5.0, -5.0, 0.0).expect("set_mag_offset");
    let (ox, oy, _) = chip.get_mag_offset().expect("get_mag_offset");
    check_true!((ox - 5.0).abs() < 0.15 && (oy + 5.0).abs() < 0.15, "mag_offset_roundtrip", passed, failed);
    chip.set_mag_offset(0.0, 0.0, 0.0).expect("set_mag_offset");

    chip.reset_mag_min_max().expect("reset_mag_min_max");
    wait(200);
    let (mn, mxv) = chip.read_mag_min_max().expect("read_mag_min_max");
    check_true!(mn.0 <= mxv.0 && mn.1 <= mxv.1 && mn.2 <= mxv.2, "min_max_ordered", passed, failed);
    let heading = chip.heading(0.0).expect("heading");
    check_true!(heading >= 0.0 && heading < 360.0, "heading_in_range", passed, failed);

    check_true!(chip.set_fifo(1, 8).expect("set_fifo"), "fifo_enabled", passed, failed);
    wait(500);
    check_true!(chip.fifo_level().expect("fifo_level") <= 32, "fifo_level_in_range", passed, failed);
    let mut samples = [(0.0f32, 0.0f32, 0.0f32); 4];
    check_true!(chip.read_fifo(&mut samples).expect("read_fifo") <= 4, "fifo_samples_bounded", passed, failed);
    chip.set_fifo(0, 0).expect("set_fifo");

    chip.set_freefall_motion(true, 7, 0.3, 50.0, true, false).expect("set_freefall_motion");
    let _ = chip.read_motion_source().expect("read_motion_source");
    chip.set_transient(7, 0.3, 0.0, false, false).expect("set_transient");
    let _ = chip.read_transient_source().expect("read_transient_source");
    chip.set_pulse(&PulseConfig { single: 7, ..Default::default() }).expect("set_pulse");
    let _ = chip.read_pulse_source().expect("read_pulse_source");
    check_true!(chip.set_orientation(true, 100.0, 45, 4, 4, 2).expect("set_orientation"), "orientation_configured", passed, failed);
    let (_, _, pl, _) = chip.read_orientation().expect("read_orientation");
    check_true!(pl <= 3, "orientation_in_range", passed, failed);

    chip.enable_interrupt(FXOS8700_SOURCE_DATA_READY, 1).expect("enable_interrupt");
    let _ = chip.poll_interrupt().expect("poll_interrupt");
    chip.disable_interrupt(FXOS8700_SOURCE_DATA_READY).expect("disable_interrupt");

    let (_, _, _, dz) = chip.self_test().expect("self_test");
    check_true!(dz > 0, "self_test_z_positive", passed, failed);
    chip.set_mode(2).expect("set_mode");
    check_true!(chip.measure_mag_once().expect("measure_mag_once").is_some(), "measure_mag_once", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
