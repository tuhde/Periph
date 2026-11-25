#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::{Ads7828Full};

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
        const DIVIDER: f32 = 2.0; // inputs sit behind 2:1 dividers (rails up to 5 V)

        // --- Eight-channel supply monitor ---
        // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
        // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
        let mut vmin = [99.0f32; 8];
        let mut vmax = [0.0f32; 8];
        for _ in 0..5 {
            let mut raws = [0u16; 8];
            adc.read_scan(&[0, 1, 2, 3, 4, 5, 6, 7], &mut raws).expect("scan"); // Scan channels, (channels, out) → Result<(), E>
            for ch in 0..8 {
                let volts = raws[ch] as f32 * adc.lsb() * DIVIDER;
                if volts < vmin[ch] { vmin[ch] = volts; }
                if volts > vmax[ch] { vmax[ch] = volts; }
                println!("ch{}={} ({:.3} V)", ch, raws[ch], volts);
            }
            delay.delay_ms(1000);
        }
        println!("min={:?} max={:?}", vmin, vmax);

        // --- Read a bridge sensor on CH0/CH1 ---
        // The signed differential read does two conversions (normal and inverted
        // polarity) so the sign of the bridge output is preserved.
        let mv = adc.read_signed_differential(0).expect("signed") * 1000.0; // Read pair 0 signed, (pair=0–3) → Result<f32, E> V
        println!("bridge {:+.2} mV", mv);

        // --- Second scan against an external 5.0 V reference ---
        // Switching to a 5 V external reference gives full-scale range up to VDD.
        adc.set_reference(Some(5.0)).expect("set_reference");   // Use external reference, (vref Option<f32> V) → Result<(), E>
        let mut ext = [0u16; 8];
        adc.read_scan(&[0, 1, 2, 3, 4, 5, 6, 7], &mut ext).expect("scan"); // Scan channels, (channels, out) → Result<(), E>
        println!("ext 5 V scan ch0={} ch7={}", ext[0], ext[7]);

        // --- Finish in full power-down ---
        adc.power_down().expect("power_down");                  // Power down completely, () → Result<(), E>
        adc.set_reference(None).expect("set_reference");
        delay.delay_ms(1000);
    }
}
