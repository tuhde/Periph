//go:build linux && !tinygo

// APDS-9250 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/light"
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

func partOK(s *light.APDS9250Full) bool {
	p, _, err := s.PartID()
	return err == nil && p == 0xB
}

func gainIs(s *light.APDS9250Full, want uint8) bool {
	v, err := s.GetGain()
	return err == nil && v == want
}

func resIs(s *light.APDS9250Full, want uint8) bool {
	v, err := s.GetResolution()
	return err == nil && v == want
}

func rateIs(s *light.APDS9250Full, want uint16) bool {
	v, err := s.GetMeasurementRate()
	return err == nil && v == want
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fail("I2C_BUS", err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x52"), 0, 8)
	if err != nil {
		fail("I2C_ADDR", err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fail("i2c", err)
	}
	defer conn.Close()

	sensor, err := light.NewAPDS9250Full(conn)
	if err != nil {
		fail("init", err)
	}
	check("part_id", partOK(sensor))
	bits, _ := sensor.GetResolution()
	rate, _ := sensor.GetMeasurementRate()
	gain, _ := sensor.GetGain()
	check("defaults", bits == 18 && rate == 100 && gain == 3)
	c, err := sensor.Read()
	check("read_range", err == nil && c.Red <= 0xFFFFF && c.Green <= 0xFFFFF && c.Blue <= 0xFFFFF && c.IR <= 0xFFFFF)
	check("gain_18", sensor.SetGain(18) == nil && gainIs(sensor, 18))
	_ = sensor.SetGain(3)
	check("resolution_16", sensor.SetResolution(16) == nil && resIs(sensor, 16))
	tint, _ := sensor.IntegrationTime()
	check("integration_time", near(tint, 0.025, 1e-4))
	_ = sensor.SetResolution(18)
	check("rate_200", sensor.SetMeasurementRate(200) == nil && rateIs(sensor, 200))
	_ = sensor.SetMeasurementRate(100)
	cc, err := sensor.ReadChannels()
	check("read_channels_range", err == nil && cc.Green <= 0xFFFFF)
	g, err := sensor.ReadGreen()
	check("read_single_green", err == nil && g <= 0xFFFFF)
	_ = sensor.SetMode(false)
	ir, err := sensor.ReadIR()
	check("als_mode_reads", err == nil && ir <= 0xFFFFF)
	_ = sensor.SetMode(true)
	check("set_thresholds", sensor.SetThresholds(100, 200000) == nil)
	lo, hi, err := sensor.GetThresholds()
	check("thresholds_roundtrip", err == nil && lo == 100 && hi == 200000)
	check("interrupt_config", sensor.SetPersistence(3) == nil && sensor.SetVariance(64) == nil && sensor.SetInterruptChannel(light.APDS9250ChannelGreen) == nil)
	_ = sensor.EnableInterrupt(light.APDS9250SourceVariation)
	_ = sensor.DisableInterrupt()
	_, _ = sensor.Status()
	st, _ := sensor.PollInterrupt()
	check("status_clean", st&light.APDS9250StatusInterrupt == 0)
	_ = sensor.Disable()
	_ = sensor.Enable()
	_ = sensor.SoftReset()
	check("soft_reset_defaults", gainIs(sensor, 3) && resIs(sensor, 18))

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
