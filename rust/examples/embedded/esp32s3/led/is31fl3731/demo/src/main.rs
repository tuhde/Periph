#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::is31fl3731::{Is31fl3731Full, ROWS, GAMMA_64};

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

    // --- Animated matrix: auto-play movie, blinking checkerboard, breath, gamma fade ---
    // A diagonal moves across frames 1-8 and is played by the chip itself, then a
    // blinking checkerboard is shown in Picture mode, a filled frame breathes, the
    // matrix fades through the 64-step gamma table, a finite 2-loop movie ends (polled
    // with movie_finished) and the display is shut down.
    let mut matrix = Is31fl3731Full::new(i2c, ADDR).expect("init IS31FL3731"); // Create IS31FL3731 driver, (i2c, addr=0x74)

    matrix.clear_all_frames().unwrap();                         // Initialise all eight frames, () → ()
    for f in 0..8u8 {
        matrix.select_frame(f).unwrap();                        // Draw on frame f, (frame 0–7) → bool
        for r in 0..ROWS {
            matrix.set_pixel(r, (f + r) % 16, 255).unwrap();    // One diagonal LED per row, (row, col, brightness) → bool
        }
    }
    matrix.select_frame(0).unwrap();
    matrix.start_auto_play(0, 0, 100, 0).unwrap();              // Endless 8-frame movie, (frames, loops, delay_ms, start_frame) → bool
    for _ in 0..6 {
        println!("{:?}", matrix.current_frame().unwrap());      // Frame being displayed, () → u8
        delay.delay_millis(500);
    }
    matrix.stop_auto_play().unwrap();                           // Picture mode, current frame kept, () → ()
    matrix.select_frame(0).unwrap();
    matrix.clear().unwrap();                                    // Blank the drawing frame, () → ()
    let mut board = [0u8; 144];
    for r in 0..9usize {
        for c in 0..16usize { board[r * 16 + c] = if (r + c) % 2 == 0 { 255 } else { 0 }; }
    }
    matrix.set_pixels(&board).unwrap();                         // Checkerboard, (data 144 bytes) → bool
    matrix.set_blink_all(true).unwrap();                        // Every LED blinks, (on) → ()
    matrix.enable_blink(true, 2).unwrap();                      // Blink period 2 × 0.27 s, (enabled, period) → bool
    matrix.show_frame(0).unwrap();                              // Show frame 1 in Picture mode, (frame) → bool
    delay.delay_millis(3000);
    matrix.enable_blink(false, 1).unwrap();
    matrix.set_blink_all(false).unwrap();
    matrix.fill(255).unwrap();                                  // Full brightness, (brightness) → ()
    matrix.set_breath(true, 4, 4, 2).unwrap();                  // Breathing, (enabled, fade_in, fade_out, extinguish) → bool
    delay.delay_millis(5000);
    matrix.set_breath(false, 0, 0, 0).unwrap();
    for level in GAMMA_64 {
        matrix.fill(level).unwrap();                            // Gamma-corrected fade, (brightness) → ()
        delay.delay_millis(30);
    }
    matrix.set_picture_mode().unwrap();                         // Picture mode, () → ()
    matrix.start_auto_play(0, 2, 100, 0).unwrap();              // Two loops, (frames, loops, delay_ms, start_frame) → bool
    for _ in 0..30 {
        delay.delay_millis(100);
        if matrix.movie_finished().unwrap() { break; }          // Poll the movie-finished flag, () → bool
    }
    matrix.clear_all_frames().unwrap();
    matrix.shutdown(true).unwrap();                             // Display off, (on) → ()
    println!("===DONE: 0 passed, 0 failed===");
    loop {}
}
