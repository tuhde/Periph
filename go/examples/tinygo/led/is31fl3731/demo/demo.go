//go:build tinygo

// IS31FL3731 demo example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func show[T any](v T, err error) {
	must(err)
	fmt.Println(v)
}

func main() {
	i2c := machine.I2C1
	must(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x74, nil, nil) // Create I2C connection, (i2c, addr=0x74) → (*I2CConnection)

	// --- Animated matrix: auto-play movie, blinking checkerboard, breath, gamma fade ---
	// A diagonal moves across frames 1-8 and is played by the chip itself, then a
	// blinking checkerboard is shown in Picture mode, a filled frame breathes, the
	// matrix fades through the 64-step gamma table, a finite 2-loop movie ends (polled
	// with MovieFinished) and the display is shut down.

	matrix, err := led.NewIs31fl3731Full(conn) // Create IS31FL3731 driver, (connection) → (*Is31fl3731Full, error)
	if err != nil {
		panic(err)
	}

	must(matrix.ClearAllFrames()) // Initialise all eight frames, () → error
	for f := uint8(0); f < 8; f++ {
		must(matrix.SelectFrame(f)) // Draw on frame f, (frame 0–7) → error
		for r := uint8(0); r < led.Is31fl3731Rows; r++ {
			must(matrix.SetPixel(r, (f+r)%16, 255)) // One diagonal LED per row, (row, col, brightness) → error
		}
	}
	must(matrix.SelectFrame(0))
	must(matrix.StartAutoPlay(0, 0, 100, 0)) // Endless 8-frame movie, (frames, loops, delayMs, startFrame) → error
	for i := 0; i < 6; i++ {
		show(matrix.CurrentFrame()) // Frame being displayed, () → (uint8, error)
		time.Sleep(500 * time.Millisecond)
	}
	must(matrix.StopAutoPlay()) // Picture mode, current frame kept, () → error
	must(matrix.SelectFrame(0))
	must(matrix.Clear()) // Blank the drawing frame, () → error
	board := make([]byte, 144)
	for r := 0; r < 9; r++ {
		for c := 0; c < 16; c++ {
			if (r+c)%2 == 0 {
				board[r*16+c] = 255
			}
		}
	}
	must(matrix.SetPixels(board))     // Checkerboard, (data 144 bytes) → error
	must(matrix.SetBlinkAll(true))    // Every LED blinks, (on) → error
	must(matrix.EnableBlink(true, 2)) // Blink period 2 × 0.27 s, (enabled, period) → error
	must(matrix.ShowFrame(0))         // Show frame 1 in Picture mode, (frame) → error
	time.Sleep(3 * time.Second)
	must(matrix.EnableBlink(false, 1))
	must(matrix.SetBlinkAll(false))
	must(matrix.Fill(255))                // Full brightness, (brightness) → error
	must(matrix.SetBreath(true, 4, 4, 2)) // Breathing, (enabled, fadeIn, fadeOut, extinguish) → error
	time.Sleep(5 * time.Second)
	must(matrix.SetBreath(false, 0, 0, 0))
	for _, level := range led.Is31fl3731Gamma64 {
		must(matrix.Fill(level)) // Gamma-corrected fade, (brightness) → error
		time.Sleep(30 * time.Millisecond)
	}
	must(matrix.SetPictureMode())            // Picture mode, () → error
	must(matrix.StartAutoPlay(0, 2, 100, 0)) // Two loops, (frames, loops, delayMs, startFrame) → error
	for i := 0; i < 30; i++ {
		time.Sleep(100 * time.Millisecond)
		if done, _ := matrix.MovieFinished(); done { // Poll the movie-finished flag, () → (bool, error)
			break
		}
	}
	must(matrix.ClearAllFrames())
	must(matrix.Shutdown(true)) // Display off, (on) → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}
