use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::adc_dac::{Es8311Full, Es8311AdcConfig as AdcConfig, Es8311AlcConfig as AlcConfig, Es8311DrcConfig as DrcConfig, Es8311AdcdatMode as AdcdatMode, Es8311SerialConfig as SerialConfig};
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
    let mut codec = Es8311Full::new(dev, addr, 12_288_000, 48_000, &mut delay).expect("init ES8311"); // Create ES8311 driver and run the init sequence, (i2c, addr, mclk_hz=12_288_000 Hz, sample_rate_hz=48_000 Hz, delay) → Result<Self, Es8311Error<E>>


    // --- Bring up the codec for a voice assistant ---
    // The control port only configures the codec; the I2S stream (MCLK, BCLK,
    // LRCK, DSDIN, ASDOUT) is produced elsewhere. 16 kHz is the usual rate for
    // speech recognition.
    codec.configure_clock(12_288_000, 16_000, false, false).expect("clock");                        // Program the clock tree, (mclk_hz Hz, sample_rate_hz Hz, mclk_from_bclk, mclk_invert) → Result<(), Es8311Error<E>>
    codec.configure_serial(SerialConfig::default()).expect("serial");                               // Configure the digital audio port, (SerialConfig{format=I2s, word_length=16}) → Result<(), Es8311Error<E>>

    // --- Configure the microphone path ---
    // 24 dB analog gain plus the ALC keeps quiet and loud talkers at a similar
    // level; the noise gate and automute silence the line between utterances.
    codec.set_mic_gain(24.0).expect("mic gain");                                                    // Set microphone PGA gain, (db 0–30 dB) → Result<(), E>
    let g = codec.get_mic_gain().expect("get mic gain");                                            // Read back PGA gain, () → Result<f32, E> dB
    println!("mic gain dB {:.1}", g);
    codec.set_adc_volume(0.0).expect("adc volume");                                                 // Set ADC digital volume, (db −95.5–32 dB) → Result<(), E>
    codec.configure_alc(AlcConfig { enabled: true, max_level_db: -12.0, min_level_db: -18.1, automute: true, noise_gate_db: -60.0, ..Default::default() }).expect("alc"); // Configure ALC, (AlcConfig{enabled, max_level_db, min_level_db, automute, noise_gate_db}) → Result<(), E>
    codec.configure_adc(AdcConfig::default()).expect("adc");                                        // Configure ADC path, (AdcConfig{scale_db=24 dB, osr=64, hpf=true}) → Result<(), Es8311Error<E>>

    // --- Configure playback ---
    // The DRC compresses loud peaks so a small speaker does not clip; the soft
    // ramp avoids clicks while the volume changes.
    codec.configure_drc(DrcConfig { enabled: true, max_level_db: -12.0, min_level_db: -24.1, window: 0 }).expect("drc"); // Configure DRC, (DrcConfig{enabled, max_level_db, min_level_db, window}) → Result<(), E>
    codec.set_dac_ramp(8).expect("dac ramp");                                                       // Set DAC soft-ramp rate, (rate 0–15) → Result<(), E>
    codec.set_mute(false).expect("unmute");                                                         // Unmute the DAC, (muted=false) → Result<(), E>

    // --- Ramp the playback volume ---
    // Step from -60 dB to -10 dB, reading the value back each time.
    for vol in (-60..=-10).step_by(10) {
        codec.set_volume(vol as f32).expect("volume");                                              // Set DAC volume, (db −95.5–32 dB) → Result<(), E>
        let v = codec.get_volume().expect("get volume");                                            // Read back DAC volume, () → Result<f32, E> dB
        println!("volume dB {:.1}", v);
        sleep(Duration::from_millis(500));
    }

    // --- Echo-cancellation feedback and loopback ---
    // "DacAdc" puts the DAC data next to the microphone on ADCDAT so the host
    // can cancel the speaker echo; the loopback routes the microphone to the
    // speaker for a quick acoustic check.
    codec.set_adcdat_mode(AdcdatMode::DacAdc).expect("adcdat");                                     // Select ADCDAT content, (mode AdcdatMode::*) → Result<(), E>
    sleep(Duration::from_millis(2000));
    codec.set_adcdat_mode(AdcdatMode::AdcAdc).expect("adcdat");                                     // Restore ADCDAT content, (mode AdcdatMode::AdcAdc) → Result<(), E>
    codec.set_loopback(true).expect("loopback");                                                    // Route ADC output into the DAC, (enabled) → Result<(), E>
    sleep(Duration::from_millis(2000));
    codec.set_loopback(false).expect("loopback off");                                               // Disable loopback, (enabled=false) → Result<(), E>

    // --- Shut down ---
    // Mute first so power-down does not pop, then enter standby.
    codec.set_mute(true).expect("mute");                                                            // Mute the DAC, (muted=true) → Result<(), E>
    codec.power_down().expect("power down");                                                        // Enter standby, () → Result<(), E>
}
