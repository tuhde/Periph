#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::accelerometer::{
    Lsm303Full, MODE_HIGH_RESOLUTION, FIFO_STREAM, MAG_MODE_CONTINUOUS, SOURCE_CLICK,
};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = match Lsm303Full::new(i2c) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach the LSM303 magnetometer at 0x1E");
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    // --- Minimal: acceleration ---
    let accel_ok = chip
        .read_acceleration()
        .map(|(x, y, z)| x.is_finite() && y.is_finite() && z.is_finite())
        .unwrap_or(false);
    check_true!(accel_ok, "read_acceleration_ok", passed, failed);

    let magnitude_ok = chip
        .read_acceleration()
        .map(|(x, y, z)| {
            let m = (x * x + y * y + z * z).sqrt();
            m >= 0.5 && m <= 1.5
        })
        .unwrap_or(false);
    check_true!(magnitude_ok, "acceleration_magnitude_near_1g", passed, failed);

    // --- Minimal: magnetic field ---
    let mag_ok = chip
        .read_magnetic()
        .map(|(x, y, z)| x.is_finite() && y.is_finite() && z.is_finite())
        .unwrap_or(false);
    check_true!(mag_ok, "read_magnetic_ok", passed, failed);

    // --- Full: configuration ---
    check_true!(chip.set_range(4).is_ok(), "set_range_ok", passed, failed);
    check_true!(chip.set_data_rate(50).is_ok(), "set_data_rate_ok", passed, failed);
    check_true!(chip.set_mode(MODE_HIGH_RESOLUTION).is_ok(), "set_mode_ok", passed, failed);
    check_true!(chip.set_axes(true, true, true).is_ok(), "set_axes_ok", passed, failed);
    check_true!(chip.new_data_available().is_ok(), "new_data_available_ok", passed, failed);
    check_true!(chip.read_status().is_ok(), "read_status_ok", passed, failed);

    // --- Full: high-pass filter and FIFO ---
    check_true!(
        chip.set_high_pass(0, 0, false, true, false, false).is_ok(),
        "set_high_pass_ok",
        passed,
        failed
    );
    check_true!(chip.reset_high_pass().is_ok(), "reset_high_pass_ok", passed, failed);
    check_true!(chip.set_fifo(FIFO_STREAM, 16, false).is_ok(), "set_fifo_ok", passed, failed);
    check_true!(chip.fifo_level().is_ok(), "fifo_level_ok", passed, failed);
    check_true!(chip.read_fifo_status().is_ok(), "read_fifo_status_ok", passed, failed);

    // --- Full: interrupt generators ---
    check_true!(
        chip.set_motion_interrupt(1, 0x02, 0.5, 10.0, false).is_ok(),
        "set_motion_interrupt_ok",
        passed,
        failed
    );
    check_true!(chip.set_free_fall(1, 0.35, 30.0).is_ok(), "set_free_fall_ok", passed, failed);
    check_true!(chip.set_latch(1, true).is_ok(), "set_latch_ok", passed, failed);
    check_true!(
        chip.read_interrupt_source(1).is_ok(),
        "read_interrupt_source_ok",
        passed,
        failed
    );
    check_true!(
        chip.set_click(0, 0x04, 0.8, 20.0, 40.0, 200.0).is_ok(),
        "set_click_ok",
        passed,
        failed
    );
    check_true!(chip.read_click_source().is_ok(), "read_click_source_ok", passed, failed);
    check_true!(
        chip.enable_interrupt(SOURCE_CLICK, 1).is_ok(),
        "enable_interrupt_ok",
        passed,
        failed
    );
    check_true!(
        chip.disable_interrupt(SOURCE_CLICK, 1).is_ok(),
        "disable_interrupt_ok",
        passed,
        failed
    );

    // --- Full: magnetometer ---
    check_true!(chip.set_mag_range(250).is_ok(), "set_mag_range_ok", passed, failed);
    check_true!(chip.set_mag_data_rate(30.0).is_ok(), "set_mag_data_rate_ok", passed, failed);
    check_true!(
        chip.set_mag_mode(MAG_MODE_CONTINUOUS).is_ok(),
        "set_mag_mode_ok",
        passed,
        failed
    );
    let identity_ok = chip
        .read_mag_identity()
        .map(|id| id == [0x48, 0x34, 0x33])
        .unwrap_or(false);
    check_true!(identity_ok, "mag_identity_matches", passed, failed);
    check_true!(chip.enable_temperature(true).is_ok(), "enable_temperature_ok", passed, failed);
    check_true!(
        chip.read_temperature_delta().is_ok(),
        "read_temperature_delta_ok",
        passed,
        failed
    );

    // --- Full: compass heading ---
    let heading_ok = chip
        .heading(false)
        .map(|h| h >= 0.0 && h < 360.0)
        .unwrap_or(false);
    check_true!(heading_ok, "heading_in_range", passed, failed);

    // --- Full: interrupt status ---
    check_true!(chip.poll_interrupt().is_ok(), "poll_interrupt_ok", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
