use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::motor::{
    Drv2605Minimal, DRV2605_EFFECT_BUZZ, DRV2605_EFFECT_DOUBLE_CLICK, DRV2605_EFFECT_STRONG_CLICK,
};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut haptic = Drv2605Minimal::new(dev, &mut delay, false).expect("init DRV2605"); // Create DRV2605 driver, (i2c, delay, lra=false)

    for effect in [DRV2605_EFFECT_STRONG_CLICK, DRV2605_EFFECT_DOUBLE_CLICK, DRV2605_EFFECT_BUZZ] {
        haptic.play_effect(effect).expect("play"); // Play a ROM effect, (effect_id 1–123) → ()
        while haptic.is_playing().expect("is_playing") { // Check playback, () → bool
            sleep(Duration::from_millis(10));
        }
        sleep(Duration::from_millis(500));
    }

    haptic.stop().expect("stop"); // Cancel playback, () → ()
}
