#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::{Ads7828Full, ADS7828_REF_ON_ADC_ON};

esp_app_desc!();

const ADDR: u8 = 0x48;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut adc = Ads7828Full::new(i2c, Delay::new(), ADDR, None); // Create ADS7828 driver, (i2c, delay, addr=0x48, vref None → internal 2.5 V)
    adc.init().expect("init");                                  // Enable internal reference, () → Result<(), E>

    loop {
        let raw = adc.read_raw(3).expect("read_raw");           // Read CH3 raw code, (channel=0–7) → Result<u16, E>
                                                                // single-ended, interleaved channel encoding; returns 0–4095
        let v3 = adc.read_voltage(3).expect("read_voltage");    // Read CH3 voltage, (channel=0–7) → Result<f32, E> V
                                                                // raw / 4096 × vref
        let all = adc.read_all().expect("read_all");            // Read all eight channels, () → Result<[f32; 8], E> V
                                                                // one conversion per channel, CH0…CH7
        let draw = adc.read_differential_raw(0, false).expect("diff raw"); // Read pair 0 raw code, (pair=0–3, inverted) → Result<u16, E>
                                                                // CH0 (+) / CH1 (−); a negative difference reads 0
        let dv = adc.read_differential(1, true).expect("diff"); // Read pair 1 inverted, (pair=0–3, inverted) → Result<f32, E> V
                                                                // CH3 (+) / CH2 (−)
        let sv = adc.read_signed_differential(0).expect("signed"); // Read pair 0 signed, (pair=0–3) → Result<f32, E> V
                                                                // two conversions (normal and inverted), range −vref … +vref
        let lsb = adc.lsb();                                    // Get code step, () → f32 V
                                                                // vref / 4096 (610 µV at 2.5 V)
        let mut scan = [0u16; 4];
        adc.read_scan(&[0, 2, 4, 6], &mut scan).expect("scan"); // Scan channels, (channels, out) → Result<(), E>
                                                                // one raw conversion per channel in the given order
        adc.set_reference(Some(5.0)).expect("set_reference");   // Use external reference, (vref Option<f32> V) → Result<(), E>
                                                                // PD1 = 0 from now on; the reference must already be stable
        let ext = adc.get_reference();                          // Get reference voltage, () → f32 V
        let internal = adc.uses_internal_reference();           // Internal reference selected?, () → bool
        adc.set_reference(None).expect("set_reference");        // Use internal reference, (vref Option<f32> V) → Result<(), E>
                                                                // re-runs the reference turn-on and 1 ms settle wait
        adc.set_power_mode(ADS7828_REF_ON_ADC_ON).expect("set_power_mode"); // Set PD bits, (pd=0–3) → Result<(), Ads7828Error>
                                                                // internal reference requires PD1 = 1 (values 2 or 3)
        adc.power_down().expect("power_down");                  // Power down completely, () → Result<(), E>
                                                                // lone command with PD=00; next read re-enables the reference
        println!("raw={} v3={:.3} draw={} dv={:.3} sv={:.3} lsb={:.6} ext={} internal={} scan0={} all7={:.3}", raw, v3, draw, dv, sv, lsb, ext, internal, scan[0], all[7]);
        delay.delay_ms(1000);
    }
}
