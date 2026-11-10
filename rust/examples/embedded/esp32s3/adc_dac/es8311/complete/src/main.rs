#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::adc_dac::{Es8311Full, Es8311AdcConfig as AdcConfig, Es8311AlcConfig as AlcConfig, Es8311DrcConfig as DrcConfig, Es8311LowPower as LowPower, Es8311AdcdatMode as AdcdatMode, Es8311Input as Input, Es8311SerialConfig as SerialConfig};

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

    let mut codec = Es8311Full::new(i2c, ADDR, 12_288_000, 48_000, &mut delay).expect("init ES8311"); // Create ES8311 driver and run the init sequence, (i2c, addr, mclk_hz=12_288_000 Hz, sample_rate_hz=48_000 Hz, delay) → Result<Self, Es8311Error<E>>

    codec.set_volume(-30.0).expect("volume");                                                       // Set DAC volume, (db −95.5–32 dB) → Result<(), E>
                                                                                                    // writes 0x32 = round(db / 0.5) + 191
    let v = codec.get_volume().expect("get volume");                                                // Read back DAC volume, () → Result<f32, E> dB
                                                                                                    // (0x32 − 191) × 0.5
    println!("volume dB {:.1}", v);
    codec.set_mute(false).expect("unmute");                                                         // Unmute the DAC, (muted=false) → Result<(), E>
                                                                                                    // clears DSM and DEM mute bits of 0x31
    codec.set_mic_gain(27.0).expect("mic gain");                                                    // Set microphone PGA gain, (db 0–30 dB) → Result<(), E>
                                                                                                    // 0x14 bits 3:0 = round(db / 3)
    let g = codec.get_mic_gain().expect("get mic gain");                                            // Read back PGA gain, () → Result<f32, E> dB
                                                                                                    // 3 dB per register step
    println!("mic gain dB {:.1}", g);
    codec.configure_clock(12_288_000, 16_000, false, false).expect("clock");                        // Program the clock tree, (mclk_hz Hz, sample_rate_hz Hz, mclk_from_bclk, mclk_invert) → Result<(), Es8311Error<E>>
                                                                                                    // preset table first, then computed pre-divider / multiplier / OSR
    codec.configure_serial(SerialConfig::default()).expect("serial");                               // Configure the digital audio port, (SerialConfig{format=I2s, word_length=16, master=false, bclk_invert, lrck_invert, dac_right, bclk_div, lrck_div, continuous_bclk=true}) → Result<(), Es8311Error<E>>
                                                                                                    // 0x09 / 0x0A format and word length, slave mode
    codec.set_tristate(false, false).expect("tristate");                                            // Tri-state BCLK/LRCK and ADCDAT, (bclk_lrck=false, adcdat=false) → Result<(), E>
                                                                                                    // 0x07 bits 5:4
    codec.set_input(Input::Analog).expect("input");                                                 // Select microphone input, (source Input::Analog|Digital|None) → Result<(), E>
                                                                                                    // MIC1P / MIC1N differential, PGA powered
    codec.configure_dmic(false).expect("dmic");                                                     // Select PDM latch edge, (latch_falling_edge=false) → Result<(), E>
                                                                                                    // 0x15 bit 0
    codec.set_adc_volume(0.0).expect("adc volume");                                                 // Set ADC digital volume, (db −95.5–32 dB) → Result<(), E>
                                                                                                    // 0x17; ALC maximum gain when the ALC is on
    codec.configure_adc(AdcConfig::default()).expect("adc");                                        // Configure ADC path, (AdcConfig{scale_db=24 dB, osr=64, double_speed=false, hpf=true, hpf_coeff1=0x0C, hpf_coeff2=0x0C}) → Result<(), Es8311Error<E>>
                                                                                                    // 0x16 scale, 0x03 speed / OSR, 0x1B / 0x1C high-pass filter
    codec.set_adc_ramp(0).expect("adc ramp");                                                       // Set ADC soft-ramp rate, (rate 0–15) → Result<(), E>
                                                                                                    // 0 = off; n = 0.25 dB per 2^(n+1) LRCK
    codec.configure_alc(AlcConfig { enabled: true, max_level_db: -12.0, min_level_db: -18.1, automute: true, noise_gate_db: -60.0, ..Default::default() }).expect("alc"); // Configure ALC, (AlcConfig{enabled, max_level_db, min_level_db, window, automute, automute_window, noise_gate_db}) → Result<(), E>
                                                                                                    // 0x18–0x1A, levels rounded to the nearest listed value
    codec.configure_alc(AlcConfig::default()).expect("alc off");                                    // Disable ALC, (AlcConfig::default()) → Result<(), E>
                                                                                                    // restores defaults
    codec.bypass_adc_eq().expect("adc eq");                                                         // Bypass ADC equalizer, () → Result<(), E>
                                                                                                    // 0x1C bit 6; set_adc_eq() loads five 30-bit coefficients b0,a1,a2,b1,b2
    codec.set_dac_ramp(8).expect("dac ramp");                                                       // Set DAC soft-ramp rate, (rate 0–15) → Result<(), E>
                                                                                                    // 0x37 bits 7:4
    codec.configure_drc(DrcConfig { enabled: true, max_level_db: -12.0, min_level_db: -24.1, window: 0 }).expect("drc"); // Configure DRC, (DrcConfig{enabled, max_level_db, min_level_db, window}) → Result<(), E>
                                                                                                    // 0x34 / 0x35; DAC volume becomes the maximum gain
    codec.configure_drc(DrcConfig::default()).expect("drc off");                                    // Disable DRC, (DrcConfig::default()) → Result<(), E>
                                                                                                    // clears the DRC enable bit
    codec.bypass_dac_eq().expect("dac eq");                                                         // Bypass DAC equalizer, () → Result<(), E>
                                                                                                    // 0x37 bit 3; set_dac_eq() loads three 30-bit coefficients b0,b1,a1
    codec.set_dac_mute_options(true, true, false).expect("mute options");                           // Set DAC mute bits, (dsm=true, dem=true, dsm_target=false) → Result<(), E>
                                                                                                    // 0x31 bits 7:5 individually
    codec.set_loopback(true).expect("loopback");                                                    // Route ADC output into the DAC, (enabled) → Result<(), E>
                                                                                                    // 0x44 bit 7
    codec.set_loopback(false).expect("loopback off");                                               // Disable loopback, (enabled=false) → Result<(), E>
                                                                                                    // 0x44 bit 7 cleared
    codec.set_adcdat_mode(AdcdatMode::DacAdc).expect("adcdat");                                     // Select ADCDAT content, (mode AdcdatMode::*) → Result<(), E>
                                                                                                    // DAC + ADC: digital feedback for echo cancellation
    codec.set_adcdat_mode(AdcdatMode::AdcAdc).expect("adcdat");                                     // Restore ADCDAT content, (mode AdcdatMode::AdcAdc) → Result<(), E>
                                                                                                    // ADC data on both slots
    codec.set_sdp_mute(false, false).expect("sdp mute");                                            // Mute serial input / output, (input=false, output=false) → Result<(), E>
                                                                                                    // 0x09 / 0x0A bit 6
    codec.set_low_power(LowPower::default()).expect("low power");                                   // Clear all low-power bits, (LowPower{dac, pga, pga_out, adc, adc_ref, dac_ref, flash, integrator}) → Result<(), E>
                                                                                                    // 0x0F; all off = best THD+N
    let r = codec.read_register(0x32).expect("read register");                                      // Read raw register, (register 0x00–0xFF) → Result<u8, E>
                                                                                                    // returns the byte as stored in the chip
    println!("reg 0x32 {:.1}", r);
    codec.write_register(0x32, 0xBF).expect("write register");                                      // Write raw register, (register, value) → Result<(), E>
                                                                                                    // 0xBF = 0 dB DAC volume
    delay.delay_millis(1000);
    codec.set_mute(true).expect("mute");                                                            // Mute the DAC, (muted=true) → Result<(), E>
                                                                                                    // sets DSM and DEM mute
    codec.power_down().expect("power down");                                                        // Enter standby, () → Result<(), E>
                                                                                                    // powers down analog blocks, resets digital, clears CSM_ON
    codec.power_up().expect("power up");                                                            // Leave standby, () → Result<(), E>
                                                                                                    // re-enables analog blocks, clocks and CSM_ON
    codec.reset(&mut delay).expect("reset");                                                        // Software reset, (delay) → Result<(), E>
                                                                                                    // registers return to reset values; reconfigure afterwards

    loop {
        delay.delay_millis(1000);
    }
}
