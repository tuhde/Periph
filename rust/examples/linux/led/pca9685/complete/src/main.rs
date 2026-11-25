use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::led::pca9685::{PCA9685Full, OFF_LOW};
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

    let mut pwm = PCA9685Full::new(dev, addr);                  // Create PCA9685 driver, (i2c, addr=0x40)
    pwm.init(&mut delay).expect("init PCA9685");                // Sleep, set 1 kHz, wake, (delay) → ()
    pwm.set_duty(0, 0.25).unwrap();                             // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → ()
    pwm.set_pwm(1, 409, 1228).unwrap();                         // Phase-shifted PWM, (channel, on 0–4095, off 0–4095) → ()
                                                                // 10 % delay, 20 % duty
    println!("{:?}", pwm.get_pwm(1).unwrap());                  // Read the ON/OFF counts, (channel) → (on, off)
    pwm.set_full_on(2).unwrap();                                // Hold a channel high, (channel) → ()
    pwm.set_full_off(2).unwrap();                               // Hold a channel low, (channel) → ()
    pwm.set_all_duty(0.1).unwrap();                             // Same duty on all 16 channels, (duty 0.0–1.0) → ()
    pwm.set_all_pwm(0, 2048).unwrap();                          // Same ON/OFF counts on all channels, (on, off) → ()
    pwm.set_all_duty(0.0).unwrap();
    pwm.set_frequency(50.0, 25_000_000.0, &mut delay).unwrap(); // PWM frequency, (hz 24–1526, osc_hz, delay) → ()
    println!("{}", pwm.get_frequency(25_000_000.0).unwrap());   // Programmed PWM frequency, (osc_hz) → f32 Hz
    pwm.set_pulse_width(15, 0.0015).unwrap();                   // Servo pulse width, (channel, seconds) → ()
                                                                // 1.5 ms at 50 Hz = servo centre
    sleep_ms(1000);
    pwm.sleep().unwrap();                                       // Low-power mode, outputs stop, () → ()
    pwm.wake(&mut delay).unwrap();                              // Wake and restart running channels, (delay) → ()
    pwm.restart(&mut delay).unwrap();                           // Datasheet restart sequence, (delay) → ()
    pwm.set_output_mode(false, false, OFF_LOW).unwrap();        // MODE2 output driver, (open_drain, invert, off_state) → ()
    pwm.set_update_on_ack(false).unwrap();                      // Outputs change on STOP (false) or ACK (true), (enabled) → ()
    pwm.set_allcall(false, 0x70).unwrap();                      // LED All Call address, (enabled, address) → ()
    pwm.set_subaddress(1, 0x71, false).unwrap();                // LED Sub Call address, (index 1–3, address, enabled) → ()
    println!("{:?}", pwm.read_mode().unwrap());                 // Raw MODE1/MODE2, () → (mode1, mode2)
    // pwm.use_external_clock(&mut delay).unwrap();             // Sticky EXTCLK switch, (delay) → ()  (needs a clock on EXTCLK)
    // software_reset(&mut general_call_bus).unwrap();          // Reset every PCA9685 on the bus, (i2c) → ()
    pwm.reset().unwrap();                                       // Write power-on defaults, () → ()
    println!("===DONE: 0 passed, 0 failed===");
}
