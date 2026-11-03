use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::motor::{Drv2605Full, Drv2605Minimal, Drv2605SequenceStep};
use std::thread::sleep;
use std::time::Duration;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let mut passed = 0i32;
    let mut failed = 0i32;
    let mut delay = Delay;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut minimal = Drv2605Minimal::new(dev, &mut delay, false).expect("init DRV2605");
    minimal.play_effect(1).expect("play");
    sleep(Duration::from_millis(500));
    check_true!(!minimal.is_playing().expect("is_playing"), "effect_finishes", passed, failed);
    let dev = minimal.release();

    let mut haptic = Drv2605Full::new(dev, &mut delay, false).expect("init DRV2605 Full");
    check_true!(haptic.read_device_id().expect("id") == 3, "device_id", passed, failed);
    haptic
        .play_sequence(&[Drv2605SequenceStep::Effect(1), Drv2605SequenceStep::Wait(100), Drv2605SequenceStep::Effect(1)])
        .expect("sequence");
    check_true!(haptic.wait_done(&mut delay, 3000).expect("wait"), "sequence_finishes", passed, failed);

    haptic.rtp_level(0.5).expect("rtp");
    sleep(Duration::from_millis(100));
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
    if failed > 0 { std::process::exit(1); }
}
