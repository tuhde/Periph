#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::imu::bno055::{Bno055Full, MODE_IMU, MODE_NDOF, POWER_NORMAL, SOURCE_ACC_BSX_DRDY, TEMP_SOURCE_ACCELEROMETER};

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

    // Read fused Euler angles, () → Result<(f32, f32, f32)> rad
    // burst read of 0x1A…0x1F, raw / 900
    let (h, r, p) = imu.euler().expect("euler");
    // Read orientation quaternion, () → Result<(f32, f32, f32, f32)>
    // raw / 16384
    let (w, x, y, z) = imu.quaternion().expect("quaternion");
    // Read gravity-free acceleration, () → Result<(f32, f32, f32)> m/s²
    // raw / 100
    let lin = imu.linear_acceleration().expect("linear acceleration");
    // Read gravity vector, () → Result<(f32, f32, f32)> m/s²
    // raw / 100
    let grav = imu.gravity().expect("gravity");
    // Read raw accelerometer, () → Result<(f32, f32, f32)> m/s²
    // raw / 100
    let acc = imu.acceleration().expect("acceleration");
    // Read raw magnetometer, () → Result<(f32, f32, f32)> µT
    // raw / 16
    let mag = imu.magnetic_field().expect("magnetic field");
    // Read raw gyroscope, () → Result<(f32, f32, f32)> rad/s
    // raw / 900
    let gyr = imu.angular_rate().expect("angular rate");
    // Read temperature, () → Result<i8> °C
    // signed 8 bit, 1 LSB = 1 °C
    let t = imu.temperature().expect("temperature");
    // Read calibration status, () → Result<(u8, u8, u8, u8)> (sys, gyr, acc, mag)
    // each 0 (uncalibrated) … 3 (calibrated)
    let cal = imu.calibration_status().expect("calibration status");
    // Check full calibration, () → Result<bool>
    // true when all four values are 3
    let done = imu.is_calibrated().expect("is calibrated");
    println!("{:?} {:?} {:?} {:?} {:?} {:?} {:?} {} {:?} {}", (h, r, p), (w, x, y, z), lin, grav, acc, mag, gyr, t, cal, done);

    // Read operation mode, () → Result<u8>
    // 0x0C = NDOF
    let mode = imu.get_mode().expect("get mode");
    // Select operation mode, (mode, delay) → Result<()>
    // via CONFIGMODE, 19 ms + 7 ms waits
    imu.set_mode(MODE_IMU, &mut delay).expect("set mode");
    // Select operation mode, (mode, delay) → Result<()>
    // back to 9-DOF fusion
    imu.set_mode(MODE_NDOF, &mut delay).expect("set mode");

    // Read offsets and radii, (delay) → Result<[u8; 22]>
    // 0x55…0x6A, read in CONFIGMODE
    let blob = imu.get_calibration(&mut delay).expect("get calibration");
    // Restore offsets and radii, (data, delay) → Result<()>
    // written in CONFIGMODE
    imu.set_calibration(&blob, &mut delay).expect("set calibration");
    // Remap axes, (config, sign, delay) → Result<()>
    // 0x24/0x00 = identity placement
    imu.set_axis_remap(0x24, 0x00, &mut delay).expect("axis remap");
    // Select power mode, (mode, delay) → Result<()>
    // normal, low power or suspend
    imu.set_power_mode(POWER_NORMAL, &mut delay).expect("power mode");
    // Select temperature sensor, (source, delay) → Result<()>
    // accelerometer or gyroscope
    imu.set_temperature_source(TEMP_SOURCE_ACCELEROMETER, &mut delay).expect("temperature source");
    // Select clock source, (on, delay) → Result<()>
    // internal oscillator unless a crystal is fitted
    imu.use_external_crystal(false, &mut delay).expect("crystal");

    // Run self-test, (delay) → Result<SelfTestResult>
    // acc/mag/gyr/mcu booleans, mode restored afterwards
    let st = imu.self_test(&mut delay).expect("self test");
    // Read system status, () → Result<(u8, u8)>
    // SYS_STATUS 5 = fusion running, SYS_ERR 0 = none
    let (status, error) = imu.system_status().expect("system status");
    // Read revisions, () → Result<Revisions>
    // chip/acc/mag/gyr IDs, SW revision, bootloader
    let info = imu.revisions().expect("revisions");
    // Read unique ID, () → Result<[u8; 16]>
    // 16 bytes from page 1
    let uid = imu.unique_id().expect("unique id");
    println!("{} {:?} {} {} {:?} {:?}", mode, st, status, error, info, uid);

    // Enable interrupt source, (source) → Result<()>
    // INT_EN and INT_MSK on page 1
    imu.enable_interrupt(SOURCE_ACC_BSX_DRDY).expect("enable interrupt");
    // Poll interrupt status, () → Result<u8>
    // INT_STA, cleared on read; wire the INT pin into your own ISR and call this
    let ints = imu.poll_interrupt().expect("poll interrupt");
    // Disable interrupt source, (source) → Result<()>
    // clears the INT_EN and INT_MSK bits
    imu.disable_interrupt(SOURCE_ACC_BSX_DRDY).expect("disable interrupt");
    println!("interrupt status 0x{:02X}", ints);

    // Reset the chip, (delay) → Result<()>
    // RST_SYS, waits 650 ms, re-enters NDOF
    imu.reset(&mut delay).expect("reset");

    loop {
        delay.delay_ms(1000);
    }
}
