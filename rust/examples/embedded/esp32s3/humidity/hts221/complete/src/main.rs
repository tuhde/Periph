//! Complete example for the HTS221 — every HTS221Full method once. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::HTS221Full;

esp_app_desc!();

const ADDR: u8 = 0x5F;

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

    // Create HTS221 driver, (i2c, addr=0x5F) → HTS221
    let mut chip = HTS221Full::new(i2c, ADDR);

    // Read the calibration and start one-shot mode, () → Result<()>; decodes the per-device coefficients
    chip.init().expect("init");
    // Read WHO_AM_I, () → Result<u8>; 0xBC identifies the chip
    println!("who am i 0x{:02X}", chip.who_am_i().expect("who_am_i"));
    // Read temperature and humidity, (delay) → Result<(f32, f32)> °C, %rH; one-shot conversion
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %rH", t, rh);
    // Read humidity only, (delay) → Result<f32> %rH; one-shot, clamped to 0–100
    println!("humidity {:.1} %rH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read temperature only, (delay) → Result<f32> °C; one-shot
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Read raw humidity, (delay) → Result<i16>; signed 16-bit ADC output
    println!("raw humidity {}", chip.read_raw_humidity(&mut delay).expect("raw humidity"));
    // Read raw temperature, (delay) → Result<i16>; signed 16-bit ADC output
    println!("raw temperature {}", chip.read_raw_temperature(&mut delay).expect("raw temperature"));
    // Read calibration, () → Calibration; decoded two-point coefficients
    println!("calibration {:?}", chip.read_calibration());
    // Dew point, (delay) → Result<f32> °C; Magnus formula over one measurement
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew_point"));
    // Read STATUS_REG, () → Result<u8>; H_DA = 0x02, T_DA = 0x01
    println!("status 0x{:02X}", chip.status().expect("status"));
    // Check data available, () → Result<bool>; both H_DA and T_DA set
    println!("data ready {}", chip.data_ready().expect("data_ready"));

    // Set output data rate, (hz 0|1|7|12.5) → Result<()>; continuous sampling at 1 Hz
    chip.set_output_data_rate(1.0).expect("set_output_data_rate");
    // Get output data rate, () → Result<f32> Hz; decodes CTRL_REG1.ODR
    println!("rate {} Hz", chip.get_output_data_rate().expect("get_output_data_rate"));
    sleep_ms!(1200);
    // Read latest sample, () → Result<(f32, f32)> °C, %rH; no conversion is started
    let (t, rh) = chip.read_continuous().expect("read_continuous");
    println!("continuous {:.2} C, {:.1} %rH", t, rh);
    // Set output data rate, (hz 0|1|7|12.5) → Result<()>; back to one-shot mode
    chip.set_output_data_rate(0.0).expect("set_output_data_rate");

    // Set averaging, (temperature_samples 2–256, humidity_samples 4–512) → Result<()>; more samples, less noise
    chip.set_averaging(32, 64).expect("set_averaging");
    // Get averaging, () → Result<(u16, u16)>; (temperature samples, humidity samples)
    println!("averaging {:?}", chip.get_averaging().expect("get_averaging"));
    chip.set_averaging(16, 32).expect("set_averaging");
    // Set block data update, (enabled) → Result<()>; keeps LSB/MSB of one sample consistent
    chip.set_block_data_update(true).expect("set_block_data_update");

    // Switch the heater, (on) → Result<()>; drives off condensation, do not measure while on
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.is_heater_on().expect("is_heater_on"));
    chip.set_heater(false).expect("heater off");

    // Configure DRDY pin, (enabled, active_high, open_drain) → Result<()>; routes data-ready to the DRDY pin
    chip.configure_drdy(true, true, false).expect("configure_drdy");
    // Poll interrupt status, () → Result<u8>; STATUS_REG, flags clear when the data is read
    println!("poll 0x{:02X}", chip.poll_interrupt().expect("poll_interrupt"));
    chip.configure_drdy(false, true, false).expect("configure_drdy");

    // Power down, () → Result<()>; clears CTRL_REG1.PD
    chip.power_down().expect("power_down");
    // Power up, () → Result<()>; sets CTRL_REG1.PD
    chip.power_up().expect("power_up");
    // Reload trimming, (delay) → Result<()>; sets BOOT, waits, re-reads the calibration
    chip.reboot(&mut delay).expect("reboot");

    loop {
        sleep_ms!(1000);
    }
}
