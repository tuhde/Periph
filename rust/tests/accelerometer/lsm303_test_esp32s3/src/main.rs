#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::accelerometer::Lsm303Full;
use periph::connection::connection::Connection;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const SDA_PIN: u8 = 1;
const SCL_PIN: u8 = 2;

#[main]
fn main() -> ! {
    let mut peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0.reborrow(), Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1.reborrow())
        .with_scl(peripherals.GPIO2.reborrow());

    let mut delay = Delay::new();
    // One connection addresses both slaves: accelerometer 0x19,
    // magnetometer 0x1E.
    let conn = Connection::new(i2c);
    let mut chip = Lsm303Full::new(conn, &mut delay).expect("init LSM303");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let identity = chip.read_mag_identity().expect("read_mag_identity");
    check_true!(identity == [0x48, 0x34, 0x33], "mag_identity_H43", passed, failed);

    let (x, y, z) = chip.read_acceleration().expect("read_acceleration");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "accel_returns_floats", passed, failed);
    let magnitude = libm::sqrtf(x * x + y * y + z * z);
    check_true!(magnitude >= 0.5 && magnitude <= 1.5, "accel_magnitude_near_1g", passed, failed);

    let (mx, my, mz) = chip.read_magnetic().expect("read_magnetic");
    check_true!(mx.is_finite() && my.is_finite() && mz.is_finite(), "mag_returns_finite", passed, failed);

    let heading = chip.heading(true).expect("heading");
    check_true!(heading >= 0.0 && heading <= 360.0, "heading_in_0_360", passed, failed);

    chip.set_data_rate(50).expect("set_data_rate");
    chip.set_fifo(2, 16, false).expect("set_fifo");
    let level = chip.fifo_level().expect("fifo_level");
    check_true!(level <= 32, "fifo_level_in_range", passed, failed);
    chip.set_fifo(0, 0, false).expect("fifo_bypass");

    chip.set_click(0, 4, 0.8, 20.0, 40.0, 200.0).expect("set_click");
    chip.set_free_fall(1, 0.35, 60.0).expect("set_free_fall");
    check_true!(true, "interrupt_config_accepted", passed, failed);

    chip.enable_temperature(true).expect("enable_temperature");
    let temperature = chip.read_temperature_delta().expect("read_temperature_delta");
    check_true!(temperature.is_finite(), "temperature_finite", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop { esp_hal::delay::Delay::new().delay_millis(1000); }
}
