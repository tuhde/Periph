#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::accelerometer::lsm303::{FIFO_BYPASS, FIFO_FIFO, SOURCE_DATA_READY};
use periph::chips::accelerometer::Lsm303Full;
use periph::connection::Connection;

esp_app_desc!();

macro_rules! check {
    ($label:expr, $cond:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else { println!("FAIL {}", $label); $failed += 1; }
    };
}

#[main]
fn main() -> ! {
    let mut peripherals = esp_hal::init(esp_hal::Config::default());
    let i2c = I2c::new(peripherals.I2C0.reborrow(), Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1.reborrow())
        .with_scl(peripherals.GPIO2.reborrow());

    let mut chip = Lsm303Full::new(Connection::new(i2c), 0x19, 0x1E, Delay::new())
        .expect("init LSM303");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let id = chip.read_mag_identity().expect("read_mag_identity");            // Read magnetometer identity, () -> [u8; 3]
    check!("mag_identity", id == [0x48, 0x34, 0x33], passed, failed);

    let (ax, ay, az) = chip.read_acceleration().expect("read_acceleration");  // Read 3-axis acceleration, () -> (g, g, g)
    check!("read_acceleration_floats", ax.is_finite() && ay.is_finite() && az.is_finite(), passed, failed);
    let magnitude = libm::sqrtf(ax * ax + ay * ay + az * az);
    check!("acceleration_plausible", magnitude > 0.2 && magnitude < 10.0, passed, failed);

    let (mx, my, mz) = chip.read_magnetic().expect("read_magnetic");          // Read 3-axis magnetic field, () -> (µT, µT, µT)
    check!("read_magnetic_floats", mx.is_finite() && my.is_finite() && mz.is_finite(), passed, failed);
    let heading = chip.heading(true).expect("heading");                       // Compute compass heading, (tilt_compensate) -> deg
    check!("heading_range", (0.0..=360.0).contains(&heading), passed, failed);

    chip.set_range(4).expect("set_range");                                    // Set accelerometer range, (range_g) -> g
    chip.set_fifo(FIFO_FIFO, 8, false).expect("set_fifo");                    // Configure FIFO, (mode, watermark, trigger_int2)
    let level = chip.fifo_level().expect("fifo_level");                       // Read FIFO level, () -> u8
    check!("fifo_level_in_range", level <= 32, passed, failed);
    chip.set_fifo(FIFO_BYPASS, 0, false).expect("set_fifo");                  // Configure FIFO, (mode, watermark, trigger_int2)

    chip.set_mag_range(470).expect("set_mag_range");                          // Set magnetometer range, (range_ut) -> µT
    chip.set_mag_data_rate(30.0).expect("set_mag_data_rate");                 // Set magnetometer data rate, (rate_hz) -> Hz
    chip.enable_temperature(true).expect("enable_temperature");               // Enable temperature sensor, (enabled)
    let temp = chip.read_temperature_delta().expect("read_temperature_delta"); // Read relative temperature, () -> °C
    check!("temperature_plausible", temp.abs() < 100.0, passed, failed);

    chip.enable_interrupt(SOURCE_DATA_READY, 1).expect("enable_interrupt");   // Enable interrupt source, (source, pin)
    chip.poll_interrupt().expect("poll_interrupt");                           // Poll interrupt status, () -> u16
    chip.disable_interrupt(SOURCE_DATA_READY, 1).expect("disable_interrupt"); // Disable interrupt source, (source, pin)

    chip.reboot().expect("reboot");                                           // Reboot accelerometer memory, () -> None
    let (ax, _, _) = chip.read_acceleration().expect("read_acceleration");    // Read 3-axis acceleration, () -> (g, g, g)
    check!("read_after_reboot", ax.is_finite(), passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
