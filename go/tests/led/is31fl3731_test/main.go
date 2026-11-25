//go:build linux && !tinygo

// IS31FL3731 hardware test — Linux host.
//
// Talks to /dev/i2c-N and runs the IS31FL3731 check sequence. Prints PASS/FAIL
// per check and ends with the standard ===DONE: ... === line. Exits 0 on full
// pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x74"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	matrix, err := led.NewIs31fl3731Minimal(conn)
	if err != nil {
		check("init", false)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	check("constants", led.Is31fl3731Rows == 9 && led.Is31fl3731Cols == 16)
	check("set_pixel", matrix.SetPixel(0, 0, 255) == nil && matrix.SetPixel(8, 15, 64) == nil)
	check("set_pixel_range", matrix.SetPixel(9, 0, 0) != nil && matrix.SetPixel(0, 16, 0) != nil)
	check("fill", matrix.Fill(32) == nil)
	check("clear", matrix.Clear() == nil)

	full, err := led.NewIs31fl3731Full(conn)
	if err != nil {
		check("init_full", false)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	check("set_pixel_full", full.SetPixel(3, 4, 99) == nil)
	v1, _ := full.GetPixel(3, 4)
	v2, _ := full.GetPixel(3, 5)
	check("get_pixel", v1 == 99 && v2 == 0)
	ramp := make([]byte, 144)
	for i := range ramp {
		ramp[i] = byte(i)
	}
	check("set_pixels", full.SetPixels(ramp) == nil && full.SetPixels(ramp[:143]) != nil)
	check("set_led_enabled", full.SetLEDEnabled(0, 0, false) == nil && full.SetLEDEnabled(0, 0, true) == nil)
	check("set_blink", full.SetBlink(1, 1, true) == nil && full.SetBlinkAll(true) == nil)
	check("enable_blink", full.EnableBlink(true, 2) == nil && full.EnableBlink(true, 8) != nil)
	_ = full.EnableBlink(false, 1)
	_ = full.SetBlinkAll(false)
	check("select_frame", full.SelectFrame(1) == nil && full.SelectFrame(8) != nil)
	_ = full.Fill(10)
	check("copy_frame", full.CopyFrame(1, 2) == nil && full.CopyFrame(1, 8) != nil)
	check("show_frame", full.ShowFrame(2) == nil && full.ShowFrame(8) != nil)
	_ = full.SelectFrame(0)
	_ = full.ShowFrame(0)
	check("clear_frames", full.ClearFrame(1) == nil && full.ClearAllFrames() == nil)
	check("set_picture_mode", full.SetPictureMode() == nil)
	check("start_auto_play", full.StartAutoPlay(2, 1, 110, 0) == nil && full.StartAutoPlay(0, 0, 2000, 0) != nil)
	_, e1 := full.CurrentFrame()
	_, e2 := full.MovieFinished()
	check("frame_state", e1 == nil && e2 == nil)
	check("stop_auto_play", full.StopAutoPlay() == nil)
	check("audio", full.SetAudioMode(false) == nil && full.SetAudioSync(false) == nil && full.SetAudioRate(10) == nil &&
		full.SetAudioGain(6, true, false) == nil && full.SetAudioGain(4, false, false) != nil)
	check("set_breath", full.SetBreath(true, 2, 2, 1) == nil && full.SetBreath(false, 0, 0, 0) == nil)
	check("set_intensity_control", full.SetIntensityControl(true) == nil && full.SetIntensityControl(false) == nil)
	_, e3 := full.ReadFunctionRegister(0x03)
	check("function_register", full.WriteFunctionRegister(0x03, 0x05) == nil && e3 == nil)
	_, e4 := full.PollInterrupt()
	check("poll_interrupt", e4 == nil)
	check("shutdown", full.Shutdown(true) == nil && full.Shutdown(false) == nil)
	_ = full.Clear()

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
