#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::imu::bno055::{Bno055Full, MODE_AMG, MODE_IMU, MODE_NDOF, POWER_NORMAL, SOURCE_ACC_BSX_DRDY, TEMP_SOURCE_GYROSCOPE};

esp_app_desc!();

const ADDR: u8 = 0x29;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create BNO055 driver, (i2c, ADDR, delay) → Result
    let mut imu = Bno055Full::new(i2c, ADDR, &mut delay).expect("init");

    // --- Identify the part ---
    // Read revisions, () → Result<Revisions>
    let info = imu.revisions().expect("revisions");
    println!("chip 0x{:02X}, SW {}.{}, bootloader {}", info.chip_id, info.sw_revision >> 8, info.sw_revision & 0xFF, info.bootloader);
    // Read unique ID, () → Result<[u8; 16]>
    let uid = imu.unique_id().expect("unique id");
    println!("unique id {:02X?}", uid);

    // --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
    loop {
        // Read fused Euler angles, () → Result<(f32, f32, f32)> rad
        let (h, r, p) = imu.euler().expect("euler");
        // Read orientation quaternion, () → Result<(f32, f32, f32, f32)>
        let (w, x, y, z) = imu.quaternion().expect("quaternion");
        // Read gravity-free acceleration, () → Result<(f32, f32, f32)> m/s²
        let lin = imu.linear_acceleration().expect("linear acceleration");
        // Read gravity vector, () → Result<(f32, f32, f32)> m/s²
        let grav = imu.gravity().expect("gravity");
        // Read calibration status, () → Result<(u8, u8, u8, u8)>
        let cal = imu.calibration_status().expect("calibration status");
        println!("euler {:.2} {:.2} {:.2}  quat {:.3} {:.3} {:.3} {:.3}  lin {:.2?}  grav {:.2?}  cal {:?}", h, r, p, w, x, y, z, lin, grav, cal);
        // Check full calibration, () → Result<bool>
        if imu.is_calibrated().expect("is calibrated") {
            break;
        }
        sleep_ms!(100);
    }

    // --- Save the calibration, relative mode for 5 s, restore it ---
    // Read offsets and radii, (delay) → Result<[u8; 22]>
    let blob = imu.get_calibration(&mut delay).expect("get calibration");
    println!("calibration {:02X?}", blob);
    // Select operation mode, (mode, delay) → Result<()>
    imu.set_mode(MODE_IMU, &mut delay).expect("set mode");
    // Read operation mode, () → Result<u8>
    println!("mode {}", imu.get_mode().expect("get mode"));
    sleep_ms!(5000);
    imu.set_mode(MODE_NDOF, &mut delay).expect("set mode");
    // Restore offsets and radii, (data, delay) → Result<()>
    imu.set_calibration(&blob, &mut delay).expect("set calibration");

    // --- Self-test, temperature and configuration ---
    // Run self-test, (delay) → Result<SelfTestResult>
    println!("self-test {:?}", imu.self_test(&mut delay).expect("self test"));
    // Read temperature, () → Result<i8> °C
    println!("temperature {} C", imu.temperature().expect("temperature"));
    // Select temperature sensor, (source, delay) → Result<()>
    imu.set_temperature_source(TEMP_SOURCE_GYROSCOPE, &mut delay).expect("temperature source");
    // Remap axes, (config, sign, delay) → Result<()>
    imu.set_axis_remap(0x24, 0x00, &mut delay).expect("axis remap");
    // Select power mode, (mode, delay) → Result<()>
    imu.set_power_mode(POWER_NORMAL, &mut delay).expect("power mode");
    // Select clock source, (on, delay) → Result<()>
    imu.use_external_crystal(false, &mut delay).expect("crystal");

    // --- Raw sensors in AMG mode ---
    imu.set_mode(MODE_AMG, &mut delay).expect("set mode");
    // Read raw accelerometer, () → Result<(f32, f32, f32)> m/s²
    let acc = imu.acceleration().expect("acceleration");
    // Read raw magnetometer, () → Result<(f32, f32, f32)> µT
    let mag = imu.magnetic_field().expect("magnetic field");
    // Read raw gyroscope, () → Result<(f32, f32, f32)> rad/s
    let gyr = imu.angular_rate().expect("angular rate");
    println!("acc {:.2?}  mag {:.1?}  gyr {:.3?}", acc, mag, gyr);

    // --- Interrupts: accelerometer/fusion data ready (the caller wires INT to an ISR; here we poll) ---
    imu.set_mode(MODE_NDOF, &mut delay).expect("set mode");
    // Enable interrupt source, (source) → Result<()>
    imu.enable_interrupt(SOURCE_ACC_BSX_DRDY).expect("enable interrupt");
    sleep_ms!(500);
    // Poll interrupt status, () → Result<u8>
    println!("interrupt status 0x{:02X}", imu.poll_interrupt().expect("poll interrupt"));
    // Disable interrupt source, (source) → Result<()>
    imu.disable_interrupt(SOURCE_ACC_BSX_DRDY).expect("disable interrupt");
    // Read system status, () → Result<(u8, u8)>
    let (status, error) = imu.system_status().expect("system status");
    println!("system status {}, error {}", status, error);
    // Reset the chip, (delay) → Result<()>
    imu.reset(&mut delay).expect("reset");

    loop {
        delay.delay_ms(1000);
    }
}
