//go:build linux && !tinygo

// ISL29125 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/color"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var passed, failed int

func check(label string, cond bool) {
	if cond {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	fmt.Fprintln(os.Stderr, what+":", err)
	os.Exit(2)
}

func near(a, b, tol float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < tol
}

func idOK(s *color.ISL29125Full) bool {
	v, err := s.DeviceID()
	return err == nil && v == 0x7D
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fail("I2C_BUS", err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x44"), 0, 8)
	if err != nil {
		fail("I2C_ADDR", err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fail("i2c", err)
	}
	defer conn.Close()

	sensor, err := color.NewISL29125Full(conn)
	if err != nil {
		fail("init", err)
	}
	check("device_id", idOK(sensor))
	c, err := sensor.Read()
	check("read_range", err == nil && uint32(c.Red)+uint32(c.Green)+uint32(c.Blue) < 3*65536)
	check("set_range", sensor.SetRange(375) == nil && sensor.SetRange(10000) == nil)
	check("set_resolution", sensor.SetResolution(12) == nil && sensor.SetResolution(16) == nil)
	check("invalid_config", sensor.SetRange(100) != nil && sensor.SetResolution(8) != nil)
	check("ir_compensation", sensor.SetIRCompensation(true, 0x3F) == nil)
	_ = sensor.SetSync(false)
	_, err = sensor.ReadRGB()
	check("read_rgb", err == nil)
	_, e1 := sensor.ReadGreen()
	_, e2 := sensor.ReadRed()
	_, e3 := sensor.ReadBlue()
	check("read_single", e1 == nil && e2 == nil && e3 == nil)
	check("green_mode_reads", sensor.SetMode(color.ISL29125ModeGreen) == nil)
	_, err = sensor.ReadGreen()
	check("green_mode_value", err == nil)
	_ = sensor.SetMode(color.ISL29125ModeRGB)
	check("set_thresholds", sensor.SetThresholds(100, 60000) == nil)
	lo, hi, err := sensor.GetThresholds()
	check("thresholds_roundtrip", err == nil && lo == 100 && hi == 60000)
	check("interrupt_config", sensor.SetPersistence(2) == nil && sensor.SetInterruptChannel(color.ISL29125ChannelGreen) == nil)
	_ = sensor.EnableInterrupt(color.ISL29125SourceThreshold)
	_ = sensor.DisableInterrupt(color.ISL29125SourceThreshold)
	_, _ = sensor.Status()
	st, _ := sensor.PollInterrupt()
	check("status_clean", st&color.ISL29125StatusThreshold == 0)
	_ = sensor.SetMode(color.ISL29125ModePowerDown)
	_ = sensor.SoftReset()
	_ = sensor.SetMode(color.ISL29125ModeRGB)
	check("soft_reset_id", idOK(sensor))

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
