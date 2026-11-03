#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::motor::{Drv2605Full, Drv2605SequenceStep};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let mut delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut haptic = Drv2605Full::new(i2c, &mut delay, false).expect("init DRV2605");

    check_true!(haptic.read_device_id().expect("id") == 3, "device_id", passed, failed);
    haptic.play_effect(1).expect("play");
    delay.delay_millis(500);
    check_true!(!haptic.is_playing().expect("is_playing"), "effect_finishes", passed, failed);
    haptic
        .play_sequence(&[Drv2605SequenceStep::Effect(1), Drv2605SequenceStep::Wait(100), Drv2605SequenceStep::Effect(1)])
        .expect("sequence");
    check_true!(haptic.wait_done(&mut delay, 3000).expect("wait"), "sequence_finishes", passed, failed);

    haptic.rtp_level(0.5).expect("rtp");
    delay.delay_millis(100);
    let vdd = haptic.read_supply_voltage().expect("vdd");
    check_true!(vdd > 2.0 && vdd < 6.0, "supply_voltage_plausible", passed, failed);
    haptic.stop().expect("stop");
    check_true!(!haptic.is_playing().expect("is_playing"), "stop_clears_go", passed, failed);

    let _ = haptic.diagnose(&mut delay).expect("diagnose");
    check_true!(true, "diagnose_runs", passed, failed);
    let status = haptic.read_status().expect("status");
    check_true!(!status.over_temp, "no_overtemp", passed, failed);
    check_true!(!status.over_current, "no_overcurrent", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
