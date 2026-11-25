use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::led::pca9685::{PCA9685Full, OFF_HIGHZ, OFF_LOW};
use std::thread::sleep;
use std::time::Duration;

fn sleep_ms(ms: u64) { sleep(Duration::from_millis(ms)); }

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;

    // --- LED fader and servo sweep ---
    // Fade channel 0 at 1 kHz, show a phase-shifted pair, run a 16-channel chase,
    // switch to 50 Hz and sweep a servo on channel 15 from 1 ms to 2 ms, then show
    // all-channel writes, full ON/OFF, output modes, sleep/wake/restart with the
    // outputs preserved, the all-call and sub addresses, and finish with a reset.
    let mut pwm = PCA9685Full::new(dev, addr);                  // Create PCA9685 driver, (i2c, addr=0x40)
    pwm.init(&mut delay).expect("init PCA9685");                // Sleep, set 1 kHz, wake, (delay) → ()
    pwm.set_frequency(1000.0, 25_000_000.0, &mut delay).unwrap(); // 1 kHz PWM for LEDs, (hz, osc_hz, delay) → ()

    for i in 0..=100 {
        let duty = 0.5 - 0.5 * (core::f32::consts::PI * i as f32 / 50.0).cos();
        pwm.set_duty(0, duty).unwrap();                         // Fade up and down, (channel, duty) → ()
        sleep_ms(20);
    }

    pwm.set_pwm(1, 0, 1024).unwrap();                           // 25 % duty, no delay, (channel, on, off) → ()
    pwm.set_pwm(2, 2048, 3072).unwrap();                        // same duty, shifted by half a period
    println!("{:?}", pwm.get_pwm(2).unwrap());                  // Read back the counts, (channel) → (on, off)
    sleep_ms(1000);

    for ch in 0..16 {
        pwm.set_duty(ch, 1.0).unwrap();                         // Chase across all channels, (channel, duty) → ()
        sleep_ms(50);
        pwm.set_duty(ch, 0.0).unwrap();
    }

    pwm.set_all_duty(0.2).unwrap();                             // All channels together, (duty) → ()
    sleep_ms(500);
    pwm.set_all_pwm(0, 3072).unwrap();                          // 75 % on all channels, (on, off) → ()
    sleep_ms(500);
    pwm.set_all_duty(0.0).unwrap();
    pwm.set_full_on(3).unwrap();                                // Constant high, (channel) → ()
    sleep_ms(500);
    pwm.set_full_off(3).unwrap();                               // Constant low, (channel) → ()

    pwm.set_frequency(50.0, 25_000_000.0, &mut delay).unwrap(); // Servo frequency, (hz, osc_hz, delay) → ()
    for us in (1000..=2000).step_by(50) {
        pwm.set_pulse_width(15, us as f32 / 1e6).unwrap();      // Servo pulse 1–2 ms, (channel, seconds) → ()
        sleep_ms(50);
    }
    println!("{}", pwm.get_frequency(25_000_000.0).unwrap());   // () → f32 Hz

    pwm.set_frequency(1000.0, 25_000_000.0, &mut delay).unwrap();
    pwm.set_duty(4, 0.5).unwrap();
    pwm.set_output_mode(false, true, OFF_LOW).unwrap();         // Inverted output, (open_drain, invert, off_state) → ()
    sleep_ms(500);
    pwm.set_output_mode(true, false, OFF_HIGHZ).unwrap();       // Open-drain, high-Z when OE = 1
    sleep_ms(500);
    pwm.set_output_mode(false, false, OFF_LOW).unwrap();        // Back to totem-pole, not inverted
    pwm.set_update_on_ack(true).unwrap();                       // Update on ACK, (enabled) → ()
    pwm.set_update_on_ack(false).unwrap();

    pwm.sleep().unwrap();                                       // Outputs pause, PWM registers kept, () → ()
    sleep_ms(500);
    pwm.wake(&mut delay).unwrap();                              // Outputs resume, (delay) → ()
    pwm.restart(&mut delay).unwrap();                           // Restart sequence, (delay) → ()
    pwm.set_allcall(true, 0x70).unwrap();                       // Respond to all-call, (enabled, address) → ()
    pwm.set_allcall(false, 0x70).unwrap();
    pwm.set_subaddress(1, 0x71, true).unwrap();                 // Respond to sub address 1, (index, address, enabled) → ()
    pwm.set_subaddress(1, 0x71, false).unwrap();
    println!("{:?}", pwm.read_mode().unwrap());                 // () → (mode1, mode2)
    pwm.reset().unwrap();                                       // Back to power-on state, () → ()
    println!("===DONE: 0 passed, 0 failed===");
}
