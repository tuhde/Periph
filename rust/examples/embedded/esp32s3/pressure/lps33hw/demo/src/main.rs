#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Lps33hwFull, ODR_10_HZ};

esp_app_desc!();

const ADDR: u8 = 0x5C;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut chip = Lps33hwFull::new(i2c, ADDR).expect("init LPS33HW"); // Create LPS33HW driver, (i2c, ADDR=0x5C)

    // --- Initialization and configuration for altimeter use ---
    // 10 Hz ODR + BDU=1 + EN_LPFP=1/LPFP_CFG=1 (ODR/20) gives smooth pressure
    // readings. The barometric formula needs pressure ratios, so reducing
    // short-term noise is the dominant accuracy lever.
    chip.configure(ODR_10_HZ, true, true, 1, false, false).expect("configure");  // Configure chip, (odr=10 Hz, bdu=true, en_lpfp=true, lpfp_cfg=ODR/20, lc_en=false, sim=false) → ()
    chip.reset_lpf().expect("reset_lpf");                              // Reset LPF, () → ()
                                                                         // flushes transitory state after enabling EN_LPFP

    let sea_level_pa: f32 = 101325.0;

    // --- Main loop ---
    // Pressure is polled on P_DA rather than by fixed delay so we read the
    // freshest possible sample every cycle. With ODR=10 Hz the loop runs at
    // ~10 Hz; printing once per second gives a clean per-second summary.
    let mut n: u32 = 0;
    loop {
        let p = chip.pressure().expect("read pressure");            // Read pressure, () → f32 Pa
        let t = chip.temperature().expect("read temperature");      // Read temperature, () → f32 °C

        // --- Altitude calculation ---
        // Barometric formula: altitude_m = 44330 × (1 − (P / P0)^(1/5.255)).
        // Valid for troposphere below ~11 km; for outdoor altimetry the
        // absolute altitude depends on the local sea-level reference, but
        // relative changes (e.g. drone altitude tracking) are accurate.
        let ratio = (p / sea_level_pa) as f64;
        let altitude_m = 44330.0 * (1.0 - ratio.powf(1.0 / 5.255));
        println!("{}s: {:.2} C, {:.1} Pa, alt={:.1} m", n, t, p, altitude_m);

        // --- Autozero every 10 seconds ---
        // Re-zeroing removes slow atmospheric pressure drift for relative
        // altitude measurements. The current reading is captured into REF_P
        // and subtracted from all subsequent samples.
        if n > 0 && n % 10 == 0 {
            chip.set_autozero().expect("autozero");                  // Set AUTOZERO, () → ()
            println!("Reference updated.");
        }

        n += 1;
        delay.delay_ms(1000);
    }
}