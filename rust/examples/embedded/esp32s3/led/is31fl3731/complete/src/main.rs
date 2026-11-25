#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::is31fl3731::{Is31fl3731Full};

esp_app_desc!();

const ADDR: u8 = 0x74;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let delay = Delay::new();

    let mut matrix = Is31fl3731Full::new(i2c, ADDR).expect("init IS31FL3731"); // Create IS31FL3731 driver, (i2c, addr=0x74)

    matrix.clear().unwrap();                                    // Switch all LEDs of the drawing frame off, () → ()
    matrix.set_pixel(2, 5, 200).unwrap();                       // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    println!("{:?}", matrix.get_pixel(2, 5));                   // Read a pixel from the shadow copy, (row, col) → u8 0–255
    let mut ramp = [0u8; 144];
    for (i, v) in ramp.iter_mut().enumerate() { *v = i as u8; }
    matrix.set_pixels(&ramp).unwrap();                          // Write a whole frame, (data 144 bytes, row-major) → bool
    matrix.fill(40).unwrap();                                   // Set every LED's PWM brightness, (brightness 0–255) → ()
    matrix.set_led_enabled(0, 0, false).unwrap();               // LED-control (on/off) bit, (row, col, on) → bool
    matrix.set_led_enabled(0, 0, true).unwrap();
    matrix.set_blink(1, 1, true).unwrap();                      // Blink-enable bit of one LED, (row, col, on) → bool
    matrix.set_blink_all(false).unwrap();                       // Blink-enable bit of every LED, (on) → ()
    matrix.enable_blink(true, 2).unwrap();                      // Blink on, (enabled, period 0–7 × 0.27 s) → bool
                                                                // 0.54 s period, 50 % duty
    delay.delay_millis(2000);
    matrix.enable_blink(false, 1).unwrap();
    matrix.select_frame(1).unwrap();                            // Frame subsequent drawing writes to, (frame 0–7) → bool
    matrix.fill(100).unwrap();
    matrix.copy_frame(1, 2).unwrap();                           // Copy PWM values between frames, (src, dst) → bool
    matrix.clear_frame(2).unwrap();                             // Re-initialise one frame, (frame 0–7) → bool
    matrix.clear_all_frames().unwrap();                         // Re-initialise all eight frames, () → ()
    matrix.select_frame(0).unwrap();
    matrix.show_frame(0).unwrap();                              // Display a frame in Picture mode, (frame 0–7) → bool
    matrix.set_picture_mode().unwrap();                         // Picture mode, () → ()
    matrix.start_auto_play(2, 1, 110, 0).unwrap();              // Auto Frame Play, (frames 0–7, loops 0–7, delay_ms, start_frame) → bool
                                                                // frames/loops 0 = all/endless; plays 2 frames once
    delay.delay_millis(1000);
    println!("{:?}", matrix.frame_state().unwrap());            // Read FRAME_STATE, () → u8
    println!("{:?}", matrix.current_frame().unwrap());          // Frame being displayed, () → u8 0–7
    println!("{:?}", matrix.movie_finished().unwrap());         // Movie-finished flag (clears it), () → bool
    matrix.stop_auto_play().unwrap();                           // Back to Picture mode on the current frame, () → ()
    matrix.set_breath(true, 3, 3, 2).unwrap();                  // Breath function, (enabled, fade_in, fade_out, extinguish 0–7) → bool
    delay.delay_millis(3000);
    matrix.set_breath(false, 0, 0, 0).unwrap();
    matrix.set_intensity_control(false).unwrap();               // Share frame 1's PWM with all frames, (use_frame1) → ()
    matrix.set_audio_gain(6, true, false).unwrap();             // Audio gain, (gain_db 0–21 step 3, agc, fast) → bool
    matrix.set_audio_rate(10).unwrap();                         // Audio ADC interval, (a 0–255, × 46 µs) → ()
    matrix.set_audio_sync(false).unwrap();                      // Audio intensity modulation, (enabled) → ()
    matrix.set_audio_mode(false).unwrap();                      // Audio Frame Play on/off, (enabled) → ()
    println!("{:?}", matrix.read_function_register(0x03).unwrap());// Raw function-page read, (reg 0–0x0C) → Option<u8>
    matrix.write_function_register(0x03, 0x0B).unwrap();        // Raw function-page write, (reg, value) → bool
    println!("{:?}", matrix.poll_interrupt().unwrap());         // FRAME_STATE, clears INT, () → u8
    matrix.shutdown(true).unwrap();                             // Software shutdown, (on) → ()
    matrix.shutdown(false).unwrap();                            // Back to normal operation
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
