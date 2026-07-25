#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::environmental::{Bme680Full, OSRS_X1, OSRS_X4, OSRS_X2, MODE_SLEEP, FILTER_0, FILTER_7};

esp_app_desc!();

const ADDR: u8 = 0x76;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut bme = Bme680Full::new(i2c, ADDR).expect("init BME680"); // Create BME680 driver, (i2c, ADDR=0x76)
    let cid = bme.chip_id().expect("chip_id");                     // Read chip ID, () → u8
                                                                    // returns 0x61 for BME680
    println!("chip_id=0x{:02X}", cid);
    bme.configure(OSRS_X1, OSRS_X1, OSRS_X1, MODE_SLEEP, FILTER_0).expect("configure"); // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1, filter 0–7) → ()
                                                                    // writes ctrl_hum, config, ctrl_meas in correct order
    bme.set_oversampling(OSRS_X4, OSRS_X2, OSRS_X1).expect("set_oversampling"); // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → ()
                                                                    // changes conversion time vs resolution trade-off
    bme.set_filter(FILTER_7).expect("set_filter");                  // Set IIR filter, (coeff 0–7) → ()
                                                                    // applies to temperature and pressure only
    bme.set_heater(320, 150).expect("set_heater");                 // Configure heater profile 0, (temp_c, duration_ms) → ()
                                                                    // sets target temperature and on-time for gas measurement
    bme.set_heater_profile(1, 200, 100).expect("set_heater_profile"); // Configure heater profile 1, (index 0–9, temp_c, duration_ms) → ()
    bme.select_heater_profile(0).expect("select_profile");          // Select active profile, (index 0–9) → ()
    bme.set_gas_enabled(true).expect("set_gas");                     // Enable gas conversion, (enabled) → ()
    bme.set_heater_off(false).expect("set_heater_off");             // Control heater override, (off) → ()
    bme.set_ambient_temperature(25.0).expect("set_ambient");        // Override ambient for heater calc, (temp_c) → ()
                                                                    // re-applies the active heater profile
    let st = bme.status().expect("status");                         // Read status register, () → u8
    let (t, p, h, g) = bme.read_all().expect("read_all");           // Read all sensors in one cycle, () → (f32, f32, f32, f32)
                                                                    // returns (T, P, RH, R_gas) from single TPHG trigger
    let gv = bme.gas_valid().expect("gas_valid");                  // Check gas validity, () → bool
    let hs = bme.heater_stable().expect("heater_stable");           // Check heater stability, () → bool
    bme.reset().expect("reset");                                    // Soft reset chip, () → ()
                                                                    // re-reads calibration and re-applies configuration
    println!("T={:.1} C, P={:.1} hPa, RH={:.1} %, R_gas={:.0} Ohm", t, p, h, g);
    let _ = (st, gv, hs);
    loop {}
}
