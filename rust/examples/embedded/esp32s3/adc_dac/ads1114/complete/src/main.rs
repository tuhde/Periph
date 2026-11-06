#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::ads1114::{ADS1114_ACTIVE_LOW, ADS1114_COMP_WINDOW, ADS1114_DR_250, ADS1114_I2C_ADDRESS, ADS1114_PGA_4_096, ADS1114_QUEUE_2, ADS1114_SOURCE_CONVERSION_READY};
use periph::chips::adc_dac::Ads1114Full;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let dev = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut adc = Ads1114Full::new(dev, ADS1114_I2C_ADDRESS);                                   // Create ADS1114 Full driver, (i2c, addr=0x48)
                                                                                                // no bus traffic; Config word is rebuilt per conversion
    let raw = adc.read_raw(&mut delay).expect("read_raw");                                      // Read raw code, (delay) → Result<i16, E>
                                                                                                // single-shot conversion, signed 16-bit
    let v = adc.read_voltage(&mut delay).expect("read_voltage");                                // Read voltage, (delay) → Result<f32 V, E>
                                                                                                // raw × lsb()
    println!("raw {} voltage {:.5} V", raw, v);
    adc.set_gain(ADS1114_PGA_4_096).expect("set_gain");                                         // Set gain, (pga 0–5) → Result<(), E>
                                                                                                // ±4.096 V full scale
    println!("gain {}", adc.get_gain());
    let fs = adc.full_scale();                                                                  // Full scale, () → f32 V
                                                                                                // ±FSR of the current gain
    let lsb = adc.lsb();                                                                        // LSB, () → f32 V
                                                                                                // full_scale() / 32768
    println!("full scale {} V, lsb {} V", fs, lsb);
    adc.set_data_rate(ADS1114_DR_250).expect("set_data_rate");                                  // Set data rate, (dr 0–7) → Result<(), E>
                                                                                                // DR field of the Config word
    let sps = adc.data_rate_sps();                                                              // Data rate, () → u16 SPS
                                                                                                // samples per second of the current setting
    println!("rate {} SPS", sps);
    adc.start_continuous().expect("start_continuous");                                          // Start continuous, () → Result<(), E>
                                                                                                // MODE ← 0
    delay.delay_millis(50);
    let craw = adc.read_continuous_raw().expect("read_continuous_raw");                         // Read continuous raw, () → Result<i16, E>
                                                                                                // CONVERSION without touching Config
    let cv = adc.read_continuous().expect("read_continuous");                                   // Read continuous, () → Result<f32 V, E>
                                                                                                // read_continuous_raw() × lsb()
    println!("continuous {} {:.5} V", craw, cv);
    adc.stop_continuous().expect("stop_continuous");                                            // Stop continuous, () → Result<(), E>
                                                                                                // MODE ← 1
    let busy = adc.is_converting().expect("is_converting");                                     // Check conversion, () → Result<bool, E>
                                                                                                // CONFIG.OS == 0
    let cfg = adc.read_config().expect("read_config");                                          // Read CONFIG, () → Result<u16, E>
                                                                                                // raw 16-bit register
    println!("converting {}, config 0x{:04X}", busy, cfg);
    adc.set_thresholds(0.5, 3.0).expect("set_thresholds");                                      // Set thresholds, (low V, high V) → Result<(), E>
                                                                                                // converted with the current lsb(), clamped
    adc.set_low_threshold_raw(4000).expect("set_low_threshold_raw");                            // Set low threshold, (raw) → Result<(), E>
                                                                                                // LO_THRESH ← raw & 0xFFFF
    adc.set_high_threshold_raw(20000).expect("set_high_threshold_raw");                         // Set high threshold, (raw) → Result<(), E>
                                                                                                // HI_THRESH ← raw & 0xFFFF
    let (lo, hi) = adc.get_thresholds_raw().expect("get_thresholds_raw");                       // Get thresholds, () → Result<(i16, i16), E>
                                                                                                // read back, sign-extended
    println!("thresholds {} {}", lo, hi);
    adc.configure_comparator(ADS1114_COMP_WINDOW, ADS1114_ACTIVE_LOW, true, ADS1114_QUEUE_2).expect("configure_comparator");// Configure comparator, (mode, polarity, latch, queue) → Result<(), E>
                                                                                                // window mode, latching, 2-sample queue
    adc.clear_alert().expect("clear_alert");                                                    // Clear alert, () → Result<(), E>
                                                                                                // reads CONVERSION to release the latch
    adc.disable_comparator().expect("disable_comparator");                                      // Disable comparator, () → Result<(), E>
                                                                                                // COMP_QUE ← 11
    adc.enable_interrupt(ADS1114_SOURCE_CONVERSION_READY).expect("enable_interrupt");           // Enable interrupt, (source) → Result<(), E>
                                                                                                // ALERT/RDY signals conversion-ready
    let status = adc.poll_interrupt(None).expect("poll_interrupt");                             // Poll interrupt, (alert_pin_high: Option<bool>) → Result<u8, E>
                                                                                                // None falls back to CONFIG.OS
    println!("poll {}", status);
    adc.disable_interrupt(ADS1114_SOURCE_CONVERSION_READY).expect("disable_interrupt");         // Disable interrupt, (source) → Result<(), E>
                                                                                                // releases ALERT/RDY
    adc.reset().expect("reset");                                                                // Reset, () → Result<(), E>
                                                                                                // writes the reset values back

    loop {
        delay.delay_millis(1000);
    }
}
