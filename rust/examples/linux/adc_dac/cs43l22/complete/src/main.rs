// Complete example: exercises every method of CS43L22Full.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::cs43l22::*;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = CS43L22Full::new(dev, CS43L22_I2C_ADDRESS); // Create CS43L22 Full driver, (i2c, addr=0x4A)
    dac.init().expect("init"); // Verify ID, load defaults, () → ()
    // ID check, I2S slave defaults, required init block

    dac.start().expect("start"); // Power up the DAC once MCLK is running, () → ()
    // writes POWER_CTL_1 = 0x9E
    dac.set_volume(-30.0).expect("set_volume"); // Set master volume, (db -102–12 dB) → ()
    // code = round(db * 2) & 0xFF to registers 0x20 / 0x21
    println!("{}", dac.get_volume().expect("get_volume")); // Read back master volume, () → f32 dB
    // signed register 0x20 / 2
    println!("{:?}", dac.read_id().expect("read_id")); // Read chip ID and revision, () → (u8, u8)
    // register 0x01 split into bits 7:3 and 2:0
    dac.set_volume_channels(-20.0, -24.0).expect("set_volume_channels"); // Set per-channel master volume, (left_db, right_db) → ()
    // registers 0x20 and 0x21 independently
    dac.set_headphone_volume(-10.0, None).expect("set_headphone_volume"); // Set headphone volume, (left_db, right_db=None) → ()
    // registers 0x22 / 0x23, below -96 dB mutes
    dac.set_speaker_volume(-10.0, None).expect("set_speaker_volume"); // Set speaker volume, (left_db, right_db=None) → ()
    // registers 0x24 / 0x25
    dac.set_pcm_volume(0.0, None).expect("set_pcm_volume"); // Set PCM volume, (left_db, right_db=None) → ()
    // 7-bit signed 0.5 dB, -51.5 to +12 dB
    dac.mute_pcm(false, None).expect("mute_pcm"); // Unmute PCM channels, (left, right=None) → ()
    // bit 7 of registers 0x1A / 0x1B
    dac.set_headphone_gain(GAIN_0_6047).expect("set_headphone_gain"); // Select headphone analog gain, (index 0–7) → ()
    // HPGAIN bits 7:5 of register 0x0D
    println!("{}", dac.headphone_gain().expect("headphone_gain")); // Read headphone gain multiplier, () → f32
    // decodes HPGAIN
    dac.mute_outputs(Some(false), Some(true)).expect("mute_outputs"); // Mute outputs, (headphone=None, speaker=None) → ()
    // PLAYBACK_CTL_2 mute bits
    dac.set_outputs(OUT_ON, OUT_OFF).expect("set_outputs"); // Select amplifier power, (headphone, speaker) → ()
    // POWER_CTL_2 two bits per channel
    dac.set_speaker_mode(false, false, false).expect("set_speaker_mode"); // Configure the speaker channels, (mono, swap, mute_50_50) → ()
    // SPKMONO / SPKSWAP / MUTE50/50
    dac.set_clocking(&clocking_for(12_288_000, 48_000.0).expect("table")).expect("set_clocking"); // Configure clocking from the datasheet table, (&Clocking) → ()
    // CLOCKING_CTL register 0x05
    dac.set_interface(&Interface::default()).expect("set_interface"); // Configure the serial port, (&Interface) → ()
    // registers 0x06 / 0x07
    dac.set_tone(3.0, 3.0, 0, 0, true).expect("set_tone"); // Configure treble / bass shelves, (treble_db, bass_db, treble_corner, bass_corner, enabled) → ()
    // 1.5 dB per step, TCEN enables
    dac.set_beep(0xC, 2, 3, -6.0, BEEP_SINGLE, true).expect("set_beep"); // Play a beep, (frequency 0–15, on_time 0–15, off_time 0–7, volume_db, mode, mix) → ()
    // timing first, mode last
    dac.stop_beep().expect("stop_beep"); // Stop the beep generator, () → ()
    // BEEP[1:0] = 0
    dac.set_limiter(&Limiter { enabled: true, max_db: -6, cushion_db: -12, ..Limiter::default() }).expect("set_limiter"); // Configure the peak limiter, (&Limiter) → ()
    // registers 0x27 - 0x29
    dac.set_soft_ramp(true, false, false).expect("set_soft_ramp"); // Set soft ramp, (digital, analog_left, analog_right) → ()
    // DIGSFT and ANLGSFTx
    dac.set_zero_cross(false, false, false).expect("set_zero_cross"); // Set zero-cross, (digital, analog_left, analog_right) → ()
    // DIGZC and ANLGZCx
    dac.set_deemphasis(false).expect("set_deemphasis"); // Set de-emphasis, (enabled) → ()
    // DEEMPH bit of MISC_CTL
    dac.set_pcm_swap(SRC_NORMAL, SRC_NORMAL).expect("set_pcm_swap"); // Select PCM channel sources, (left_source, right_source) → ()
    // PCM_MIXER_SWAP
    dac.invert_pcm(false, false).expect("invert_pcm"); // Invert PCM polarity, (left, right) → ()
    // INV_PCMx
    dac.set_passthrough(false, 1, 1).expect("set_passthrough"); // Route analog inputs to the outputs, (enabled, left_input, right_input) → ()
    // PASSTHRUx and PASSxSEL
    dac.set_passthrough_volume(0.0, None).expect("set_passthrough_volume"); // Set passthrough volume, (left_db, right_db=None) → ()
    // -60 to +12 dB
    dac.mute_passthrough(true, None).expect("mute_passthrough"); // Mute passthrough, (left, right=None) → ()
    // PASSxMUTE
    dac.freeze(false).expect("freeze"); // Release the register freeze, (on) → ()
    // FREEZE bit of MISC_CTL
    dac.set_battery_compensation(false, 0, false).expect("set_battery_compensation"); // Configure battery compensation, (enabled, vp_ref_index, monitor) → ()
    // BATTERY_COMP register
    println!("{}", dac.read_vp_voltage(1.8).expect("read_vp_voltage")); // Read the VP supply voltage, (va_volts) → f32 V
    // VP_LEVEL * VA / 63.3
    println!("{:?}", dac.read_status().expect("read_status")); // Read status flags (clears them), () → Status
    // STATUS register is read-clear
    println!("{:?}", dac.read_speaker_status().expect("read_speaker_status")); // Read speaker status, () → SpeakerStatus
    // short flags and SPKR/HP pin
    dac.set_charge_pump_frequency(5).expect("set_charge_pump_frequency"); // Set charge-pump frequency divider, (n 0–15) → ()
    // f = 64 * Fs / (n + 2)
    println!("{}", dac.read_register(0x02).expect("read_register")); // Read a raw register, (reg) → u8
    // documented registers only
    dac.write_register(0x34, 0x5F).expect("write_register"); // Write a raw register, (reg, value) → ()
    // reserved and read-only registers are rejected
    dac.stop(&mut Delay).expect("stop"); // Power down the DAC, (delay) → ()
    // mute, disable ramps, POWER_CTL_1 = 0x9F, wait 100 us
}
