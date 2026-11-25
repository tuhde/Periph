#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::{Es8311Full, Es8311AlcConfig as AlcConfig, Es8311DrcConfig as DrcConfig, Es8311AdcdatMode as AdcdatMode, Es8311SerialConfig as SerialConfig};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x18;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut delay = Delay::new();
    let mut codec = Es8311Full::new(i2c, ADDR, 12_288_000, 48_000, &mut delay).expect("init ES8311");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(true, "init accepted", passed, failed);

    codec.set_volume(-20.0).unwrap();
    check_true!(codec.get_volume().unwrap() == -20.0, "set_volume/get_volume round trip", passed, failed);
    codec.set_mic_gain(24.0).unwrap();
    check_true!(codec.get_mic_gain().unwrap() == 24.0, "set_mic_gain/get_mic_gain round trip", passed, failed);
    codec.set_mute(true).unwrap();
    check_true!((codec.read_register(0x31).unwrap() & 0x60) == 0x60, "DAC mute bits set", passed, failed);
    codec.set_mute(false).unwrap();
    check_true!((codec.read_register(0x31).unwrap() & 0x60) == 0x00, "DAC mute bits cleared", passed, failed);
    check_true!(codec.configure_clock(12_288_000, 16_000, false, false).is_ok(), "configure_clock accepted", passed, failed);
    check_true!(codec.configure_serial(SerialConfig::default()).is_ok() && (codec.read_register(0x09).unwrap() & 0x1F) == 0x0C, "serial format / word length read back", passed, failed);
    codec.set_adc_volume(0.0).unwrap();
    check_true!(codec.read_register(0x17).unwrap() == 0xBF, "ADC volume 0 dB (0xBF)", passed, failed);
    codec.configure_alc(AlcConfig { enabled: true, ..Default::default() }).unwrap();
    check_true!((codec.read_register(0x18).unwrap() & 0x80) == 0x80, "ALC enabled bit", passed, failed);
    codec.configure_alc(AlcConfig::default()).unwrap();
    codec.configure_drc(DrcConfig { enabled: true, ..Default::default() }).unwrap();
    check_true!((codec.read_register(0x34).unwrap() & 0x80) == 0x80, "DRC enabled bit", passed, failed);
    codec.configure_drc(DrcConfig::default()).unwrap();
    codec.set_loopback(true).unwrap();
    check_true!((codec.read_register(0x44).unwrap() & 0x80) == 0x80, "loopback bit set", passed, failed);
    codec.set_loopback(false).unwrap();
    codec.set_adcdat_mode(AdcdatMode::DacAdc).unwrap();
    check_true!(((codec.read_register(0x44).unwrap() >> 4) & 7) == 4, "ADCDAT mode dac_adc (4)", passed, failed);
    codec.set_adcdat_mode(AdcdatMode::AdcAdc).unwrap();
    codec.set_dac_ramp(8).unwrap();
    check_true!((codec.read_register(0x37).unwrap() >> 4) == 8, "DAC ramp rate 8", passed, failed);
    codec.power_down().unwrap();
    check_true!((codec.read_register(0x00).unwrap() & 0x80) == 0x00, "power_down clears CSM_ON", passed, failed);
    codec.reset(&mut delay).unwrap();
    check_true!(true, "reset accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
