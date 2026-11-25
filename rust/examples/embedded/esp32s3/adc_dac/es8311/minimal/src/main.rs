#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::{Es8311Minimal};

esp_app_desc!();

const ADDR: u8 = 0x18;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut codec = Es8311Minimal::new(i2c, ADDR, 12_288_000, 48_000, &mut delay).expect("init ES8311"); // Create ES8311 driver and run the init sequence, (i2c, addr, mclk_hz=12_288_000 Hz, sample_rate_hz=48_000 Hz, delay) → Result<Self, Es8311Error<E>>

    codec.set_mic_gain(24.0).expect("mic gain");                                                    // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → Result<(), E>
    codec.set_mute(false).expect("unmute");                                                         // Unmute the DAC, (muted=false) → Result<(), E>
    codec.set_volume(-20.0).expect("volume");                                                       // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → Result<(), E>
    delay.delay_millis(5000);
    codec.set_mute(true).expect("mute");                                                            // Mute the DAC, (muted=true) → Result<(), E>

    loop {
        delay.delay_millis(1000);
    }
}
