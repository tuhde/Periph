use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{
    Lis3dhFull, Lis3dhMinimal, LIS3DH_FIFO_FIFO, LIS3DH_MODE_HIGH_RESOLUTION,
    LIS3DH_MODE_NORMAL, LIS3DH_SOURCE_CLICK, LIS3DH_SOURCE_DATA_READY, LIS3DH_SOURCE_IA1,
};
use periph::connection::Connection;
use std::time::Duration;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x18);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- Minimal driver --------------------------------------------------
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut minimal = Lis3dhMinimal::new(Connection::new(dev), addr, Delay).expect("init LIS3DH");

    let (x, y, z) = minimal.read().expect("read");
    check_true!(
        x.is_finite() && y.is_finite() && z.is_finite(),
        "read_returns_floats",
        passed,
        failed
    );
    let mag = (x * x + y * y + z * z).sqrt();
    check_true!(mag >= 0.5 && mag <= 1.5, "magnitude_near_1g", passed, failed);
    check_true!(z.abs() > 0.5, "z_sees_gravity", passed, failed);

    // --- Full driver -----------------------------------------------------
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lis3dhFull::new(Connection::new(dev), addr, Delay).expect("init LIS3DH Full");

    // Range / mode / data-rate selection.
    chip.set_range(4).expect("set_range");
    chip.set_mode(LIS3DH_MODE_NORMAL).expect("set_mode");
    chip.set_data_rate(100).expect("set_data_rate");
    chip.set_axes(true, true, true).expect("set_axes");
    let (x, y, z) = chip.read().expect("read");
    check_true!(
        x.is_finite() && y.is_finite() && z.is_finite(),
        "read_after_config",
        passed,
        failed
    );
    let (rx, ry, rz) = chip.read_raw().expect("read_raw");
    check_true!(
        (-512..=511).contains(&rx) && (-512..=511).contains(&ry) && (-512..=511).contains(&rz),
        "read_raw_in_range",
        passed,
        failed
    );
    let status = chip.read_status().expect("read_status");
    check_true!(status & 0x08 != 0, "read_status_zyxda", passed, failed);
    std::thread::sleep(Duration::from_millis(50));
    let ready = chip.new_data_available().expect("new_data_available");
    check_true!(ready, "new_data_available_after_50ms", passed, failed);

    // Auxiliary ADC and temperature.
    chip.enable_adc(true).expect("enable_adc");
    let v1 = chip.read_adc(1).expect("read_adc");
    check_true!(v1 >= 0.8 && v1 <= 1.6, "read_adc1_in_range", passed, failed);
    let v3 = chip.read_adc(3).expect("read_adc");
    check_true!(v3 >= 0.8 && v3 <= 1.6, "read_adc3_in_range", passed, failed);
    chip.enable_adc(false).expect("enable_adc");
    let temp = chip.read_temperature_delta().expect("read_temperature_delta");
    check_true!((-128..=127).contains(&temp), "temperature_delta_in_range", passed, failed);

    // High-pass filter.
    chip.set_high_pass(1, 1, true, true, false, false).expect("set_high_pass");
    check_true!(
        chip.reset_high_pass().is_ok(),
        "reset_high_pass_reads",
        passed,
        failed
    );
    chip.set_reference(0x20).expect("set_reference");

    // FIFO.
    chip.set_fifo(LIS3DH_FIFO_FIFO, 4, false).expect("set_fifo");
    std::thread::sleep(Duration::from_millis(250));
    let level = chip.fifo_level().expect("fifo_level");
    check_true!(level <= 32, "fifo_level_range", passed, failed);
    check_true!(level > 0, "fifo_collects_samples", passed, failed);
    let samples = chip.read_fifo(None).expect("read_fifo");
    check_true!(!samples.is_empty(), "read_fifo_returns_samples", passed, failed);
    let (watermark, overrun, empty, lvl) = chip.read_fifo_status().expect("read_fifo_status");
    check_true!(lvl <= 32, "read_fifo_status_tuple", passed, failed);
    let _ = (watermark, overrun, empty);
    chip.set_fifo(0, 0, false).expect("set_fifo");

    // Interrupt generators, click and activity.
    chip.set_motion_interrupt(1, 0x0F, 0.5, 20.0, false).expect("set_motion_interrupt");
    chip.set_free_fall(1, 0.35, 40.0).expect("set_free_fall");
    chip.set_orientation(1, false, false).expect("set_orientation");
    chip.set_latch(1, false).expect("set_latch");
    let src = chip.read_interrupt_source(1).expect("read_interrupt_source");
    check_true!(src & 0x80 == 0, "read_interrupt_source_reserved_bit", passed, failed);
    chip.set_click(0x07, 0x04, 0.5, 10.0, 20.0, 50.0, false).expect("set_click");
    let click = chip.read_click_source().expect("read_click_source");
    check_true!(click & 0x80 == 0, "read_click_source_reserved_bit", passed, failed);
    chip.set_activity(0.5, 100.0).expect("set_activity");
    chip.set_interrupt_polarity(false).expect("set_interrupt_polarity");

    // Interrupt routing and polling.
    chip.enable_interrupt(LIS3DH_SOURCE_IA1, 1).expect("enable_interrupt");
    chip.enable_interrupt(LIS3DH_SOURCE_CLICK, 1).expect("enable_interrupt");
    chip.enable_interrupt(LIS3DH_SOURCE_DATA_READY, 1).expect("enable_interrupt");
    let int_status = chip.poll_interrupt().expect("poll_interrupt");
    check_true!(int_status <= 0x7F, "poll_interrupt_range", passed, failed);
    chip.disable_interrupt(LIS3DH_SOURCE_IA1, 1).expect("disable_interrupt");
    chip.disable_interrupt(LIS3DH_SOURCE_CLICK, 1).expect("disable_interrupt");
    chip.disable_interrupt(LIS3DH_SOURCE_DATA_READY, 1).expect("disable_interrupt");

    // SDO pull-up, self-test and reboot.
    chip.set_sdo_pullup(true).expect("set_sdo_pullup");
    let st = chip.self_test(1).expect("self_test");
    check_true!(st, "self_test_passes", passed, failed);
    chip.reboot().expect("reboot");
    let (x, y, z) = chip.read().expect("read");
    let mag = (x * x + y * y + z * z).sqrt();
    check_true!(mag >= 0.5 && mag <= 1.5, "read_after_reboot", passed, failed);
    chip.set_range(2).expect("set_range");
    chip.set_mode(LIS3DH_MODE_HIGH_RESOLUTION).expect("set_mode");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
