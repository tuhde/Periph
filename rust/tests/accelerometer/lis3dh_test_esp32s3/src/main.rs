#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::accelerometer::{
    Lis3dhFull, Lis3dhMinimal, LIS3DH_FIFO_FIFO, LIS3DH_MODE_HIGH_RESOLUTION,
    LIS3DH_MODE_NORMAL, LIS3DH_SOURCE_CLICK, LIS3DH_SOURCE_IA1,
};
use periph::connection::Connection;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const SDA_PIN: u8 = 1;
const SCL_PIN: u8 = 2;
const ADDR: u8 = 0x18;

#[main]
fn main() -> ! {
    let mut peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0.reborrow(), Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1.reborrow())
        .with_scl(peripherals.GPIO2.reborrow());

    let mut delay = Delay::new();
    let mut chip = Lis3dhMinimal::new(Connection::new(i2c), ADDR, Delay::new()).expect("init LIS3DH");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let (x, y, z) = chip.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_returns_floats", passed, failed);
    let mag = libm::sqrtf(x * x + y * y + z * z);
    check_true!(mag >= 0.5 && mag <= 1.5, "magnitude_near_1g", passed, failed);
    check_true!(libm::fabsf(z) > 0.5, "z_sees_gravity", passed, failed);

    drop(chip);
    let i2c = I2c::new(peripherals.I2C0.reborrow(), Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1.reborrow())
        .with_scl(peripherals.GPIO2.reborrow());
    let mut chip_full = Lis3dhFull::new(Connection::new(i2c), ADDR, Delay::new())
        .expect("init LIS3DH Full");

    // Range / mode / data-rate selection.
    chip_full.set_range(4).expect("set_range");
    chip_full.set_mode(LIS3DH_MODE_NORMAL).expect("set_mode");
    chip_full.set_data_rate(100).expect("set_data_rate");
    chip_full.set_axes(true, true, true).expect("set_axes");
    let (x, y, z) = chip_full.read().expect("read");
    check_true!(x.is_finite() && y.is_finite() && z.is_finite(), "read_after_config", passed, failed);
    let (rx, ry, rz) = chip_full.read_raw().expect("read_raw");
    check_true!(
        (-512..=511).contains(&rx) && (-512..=511).contains(&ry) && (-512..=511).contains(&rz),
        "read_raw_in_range",
        passed,
        failed
    );

    // Auxiliary ADC and temperature.
    chip_full.enable_adc(true).expect("enable_adc");
    let v1 = chip_full.read_adc(1).expect("read_adc");
    check_true!(v1 >= 0.8 && v1 <= 1.6, "read_adc1_in_range", passed, failed);
    chip_full.enable_adc(false).expect("enable_adc");
    let temp = chip_full.read_temperature_delta().expect("read_temperature_delta");
    check_true!((-128..=127).contains(&temp), "temperature_delta_in_range", passed, failed);

    // FIFO.
    chip_full.set_fifo(LIS3DH_FIFO_FIFO, 4, false).expect("set_fifo");
    delay.delay_millis(250);
    let level = chip_full.fifo_level().expect("fifo_level");
    check_true!(level <= 32, "fifo_level_range", passed, failed);
    check_true!(level > 0, "fifo_collects_samples", passed, failed);
    let samples = chip_full.read_fifo(None).expect("read_fifo");
    check_true!(!samples.is_empty(), "read_fifo_returns_samples", passed, failed);
    chip_full.set_fifo(0, 0, false).expect("set_fifo");

    // Interrupt generators, click, activity and routing.
    chip_full.set_motion_interrupt(1, 0x0F, 0.5, 20.0, false).expect("set_motion_interrupt");
    chip_full.set_free_fall(1, 0.35, 40.0).expect("set_free_fall");
    chip_full.set_latch(1, false).expect("set_latch");
    chip_full.set_click(0x07, 0x04, 0.5, 10.0, 20.0, 50.0, false).expect("set_click");
    chip_full.set_activity(0.5, 100.0).expect("set_activity");
    chip_full.enable_interrupt(LIS3DH_SOURCE_IA1, 1).expect("enable_interrupt");
    chip_full.enable_interrupt(LIS3DH_SOURCE_CLICK, 1).expect("enable_interrupt");
    let int_status = chip_full.poll_interrupt().expect("poll_interrupt");
    check_true!(int_status <= 0x7F, "poll_interrupt_range", passed, failed);

    // Self-test and reboot.
    let st = chip_full.self_test(1).expect("self_test");
    check_true!(st, "self_test_passes", passed, failed);
    chip_full.reboot().expect("reboot");
    chip_full.set_mode(LIS3DH_MODE_HIGH_RESOLUTION).expect("set_mode");
    let (x, y, z) = chip_full.read().expect("read");
    let mag = libm::sqrtf(x * x + y * y + z * z);
    check_true!(mag >= 0.5 && mag <= 1.5, "read_after_reboot", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
