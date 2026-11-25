//go:build tinygo

// IS31FL3731 complete example — TinyGo / Raspberry Pi Pico W.
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

	matrix, err := led.NewIs31fl3731Full(conn) // Create IS31FL3731 driver, (connection) → (*Is31fl3731Full, error)
	if err != nil {
		panic(err)
	}

	must(matrix.Clear())             // Switch all LEDs of the drawing frame off, () → error
	must(matrix.SetPixel(2, 5, 200)) // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → error
	show(matrix.GetPixel(2, 5))      // Read a pixel from the shadow copy, (row, col) → (uint8, error)
	ramp := make([]byte, 144)
	for i := range ramp {
		ramp[i] = byte(i)
	}
	must(matrix.SetPixels(ramp))            // Write a whole frame, (data 144 bytes, row-major) → error
	must(matrix.Fill(40))                   // Set every LED's PWM brightness, (brightness 0–255) → error
	must(matrix.SetLEDEnabled(0, 0, false)) // LED-control (on/off) bit, (row, col, on) → error
	must(matrix.SetLEDEnabled(0, 0, true))
	must(matrix.SetBlink(1, 1, true)) // Blink-enable bit of one LED, (row, col, on) → error
	must(matrix.SetBlinkAll(false))   // Blink-enable bit of every LED, (on) → error
	must(matrix.EnableBlink(true, 2)) // Blink on, (enabled, period 0–7 × 0.27 s) → error
	// 0.54 s period, 50 % duty
	time.Sleep(2 * time.Second)
	must(matrix.EnableBlink(false, 1))
	must(matrix.SelectFrame(1)) // Frame subsequent drawing writes to, (frame 0–7) → error
	must(matrix.Fill(100))
	must(matrix.CopyFrame(1, 2))  // Copy PWM values between frames, (src, dst) → error
	must(matrix.ClearFrame(2))    // Re-initialise one frame, (frame 0–7) → error
	must(matrix.ClearAllFrames()) // Re-initialise all eight frames, () → error
	must(matrix.SelectFrame(0))
	must(matrix.ShowFrame(0))                // Display a frame in Picture mode, (frame 0–7) → error
	must(matrix.SetPictureMode())            // Picture mode, () → error
	must(matrix.StartAutoPlay(2, 1, 110, 0)) // Auto Frame Play, (frames 0–7, loops 0–7, delayMs, startFrame) → error
	// frames/loops 0 = all/endless; plays 2 frames once
	time.Sleep(time.Second)
	show(matrix.FrameState())             // Read FRAME_STATE, () → (uint8, error)
	show(matrix.CurrentFrame())           // Frame being displayed, () → (uint8, error) 0–7
	show(matrix.MovieFinished())          // Movie-finished flag (clears it), () → (bool, error)
	must(matrix.StopAutoPlay())           // Back to Picture mode on the current frame, () → error
	must(matrix.SetBreath(true, 3, 3, 2)) // Breath function, (enabled, fadeIn, fadeOut, extinguish 0–7) → error
	time.Sleep(3 * time.Second)
	must(matrix.SetBreath(false, 0, 0, 0))
	must(matrix.SetIntensityControl(false))                                                // Share frame 1's PWM with all frames, (useFrame1) → error
	must(matrix.SetAudioGain(6, true, false))                                              // Audio gain, (gainDB 0–21 step 3, agc, fast) → error
	must(matrix.SetAudioRate(10))                                                          // Audio ADC interval, (a 0–255, × 46 µs) → error
	must(matrix.SetAudioSync(false))                                                       // Audio intensity modulation, (enabled) → error
	must(matrix.SetAudioMode(false))                                                       // Audio Frame Play on/off, (enabled) → error
	show(matrix.ReadFunctionRegister(0x03))                                                // Raw function-page read, (reg 0–0x0C) → (uint8, error)
	must(matrix.WriteFunctionRegister(0x03, 0x0B))                                         // Raw function-page write, (reg, value) → error
	show(matrix.PollInterrupt())                                                           // FRAME_STATE, clears INT, () → (uint8, error)
	must(matrix.OnInterrupt(func(status uint8) { fmt.Println("movie finished", status) })) // Callback when a movie ends, (callback) → error
	must(matrix.OffInterrupt())                                                            // Unsubscribe, () → error
	must(matrix.Shutdown(true))                                                            // Software shutdown, (on) → error
	must(matrix.Shutdown(false))                                                           // Back to normal operation
	fmt.Println("===DONE: 0 passed, 0 failed===")
}
