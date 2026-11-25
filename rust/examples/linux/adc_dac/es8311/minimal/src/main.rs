use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::adc_dac::{Es8311Minimal};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x18);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut codec = Es8311Minimal::new(dev, addr, 12_288_000, 48_000, &mut delay).expect("init ES8311"); // Create ES8311 driver and run the init sequence, (i2c, addr, mclk_hz=12_288_000 Hz, sample_rate_hz=48_000 Hz, delay) → Result<Self, Es8311Error<E>>

    codec.set_mic_gain(24.0).expect("mic gain");                                                    // Set microphone PGA gain, (db 0–30 dB, 3 dB steps) → Result<(), E>
    codec.set_mute(false).expect("unmute");                                                         // Unmute the DAC, (muted=false) → Result<(), E>
    codec.set_volume(-20.0).expect("volume");                                                       // Set DAC volume, (db −95.5–32 dB, 0.5 dB steps) → Result<(), E>
    sleep(Duration::from_millis(5000));
    codec.set_mute(true).expect("mute");                                                            // Mute the DAC, (muted=true) → Result<(), E>
}
