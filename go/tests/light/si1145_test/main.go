//go:build linux && !tinygo

// Si1145 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the Si1145 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
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

	chip, _ := light.NewSi1145Full(conn)

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

	check("init", chip.Init() == nil)
	part, _, _, err := chip.ReadID()
	check("part_id", err == nil && part >= 0x45 && part <= 0x47)
	resp, err := chip.ReadResponse()
	check("response_no_error", err == nil && resp.Error == 0)
	r, err := chip.Read()
	check("read_ok", err == nil)
	check("read_uv_index_range", r.UVIndex >= 0 && r.UVIndex < 20)
	check("read_visible_positive", r.Visible > 0)
	check("read_infrared_positive", r.Infrared > 0)
	_, err = chip.ReadProximity(1)
	check("read_proximity_single", err == nil)
	uv, err := chip.ReadUVIndex()
	check("read_uv_index_single", err == nil && uv < 20)
	v, err := chip.ReadVisible()
	check("read_visible_single", err == nil && v > 0)
	v, err = chip.ReadInfrared()
	check("read_infrared_single", err == nil && v > 0)
	_, err = chip.ReadRaw()
	check("read_raw", err == nil)
	v, err = chip.ReadTemperatureRaw()
	check("read_temperature_raw", err == nil && v > 0)
	v, err = chip.ReadVDDRaw()
	check("read_vdd_raw", err == nil && v > 0)
	_ = chip.SetLEDCurrent(1, 0)
	dark, _ := chip.ReadProximity(1)
	_ = chip.SetLEDCurrent(1, 15)
	lit, _ := chip.ReadProximity(1)
	check("led_current_effect", lit >= dark)
	check("led_default", chip.SetLEDCurrent(1, 3) == nil)
	check("led_level_invalid", chip.SetLEDCurrent(1, 16) != nil)
	check("set_ps_photodiode", chip.SetPSPhotodiode(1, true) == nil)
	_ = chip.SetGain(2, 2, 0)
	v, err = chip.ReadVisible()
	check("gain_read", err == nil && v > 0)
	check("gain_default", chip.SetGain(0, 0, 0) == nil)
	_ = chip.SetHighRange(true, true, false)
	_, err = chip.ReadVisible()
	check("high_range_read", err == nil)
	check("high_range_off", chip.SetHighRange(false, false, false) == nil)
	check("set_alignment", chip.SetAlignment(false, false, false, false, false) == nil)
	check("set_uv_coefficients", chip.SetUVCoefficients(light.Si1145UCoefDefault) == nil)
	_, err = chip.ReadCalibration()
	check("read_calibration", err == nil)
	check("set_channels", chip.SetChannels(true, true, true, true, false, false, false) == nil)
	_, _ = chip.PollInterrupt()
	check("start_autonomous", chip.StartAutonomous(0.05, true, true) == nil)
	time.Sleep(500 * time.Millisecond)
	s, err := chip.PollInterrupt()
	check("autonomous_irq", err == nil && s&0x05 != 0)
	check("pause", chip.Pause() == nil)
	check("force", chip.Force(true, true) == nil)
	time.Sleep(100 * time.Millisecond)
	s, err = chip.PollInterrupt()
	check("force_irq", err == nil && s != 0)
	check("enable_interrupt", chip.EnableInterrupt(light.Si1145SourceCmd) == nil)
	check("disable_interrupt", chip.DisableInterrupt(light.Si1145SourceCmd) == nil)
	check("reset", chip.Reset() == nil)
	resp, err = chip.ReadResponse()
	check("reset_no_error", err == nil && resp.Error == 0)
	r, err = chip.Read()
	check("read_after_reset", err == nil && r.Visible > 0)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
