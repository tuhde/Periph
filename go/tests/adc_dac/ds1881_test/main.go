//go:build linux && !tinygo

// DS1881 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
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

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fail("I2C_BUS", err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x28"), 0, 8)
	if err != nil {
		fail("I2C_ADDR", err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fail("connection", err)
	}
	defer conn.Close()

	amp, err := adcdac.NewDS1881Minimal(conn)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", amp != nil && amp.Init() == nil)
	amp.SetAttenuation(0, 20)
	amp.SetAttenuation(1, 33)
	p0, _ := amp.GetPosition(0)
	p1, _ := amp.GetPosition(1)
	check("position_roundtrip", p0 == 20 && p1 == 33)
	a0, _ := amp.GetAttenuation(0)
	check("attenuation_roundtrip", a0 == 20)
	amp.Mute(0)
	p0, _ = amp.GetPosition(0)
	check("mute", p0 == 63)
	amp.SetPosition(1, 5)
	p1, _ = amp.GetPosition(1)
	check("set_position", p1 == 5)

	full, err := adcdac.NewDS1881Full(conn)
	if err != nil {
		fail("init full", err)
		return
	}
	check("init_full", full.Init() == nil)
	cfg, _ := full.GetConfig()
	check("config_default", cfg.Option == 1 && cfg.ZeroCrossing && !cfg.Nonvolatile)
	full.SetBoth(10, 40)
	r0, r1, _, _ := full.ReadAll()
	check("set_both", r0 == 10 && r1 == 40)
	full.SetAttenuationBoth(15)
	r0, r1, _, _ = full.ReadAll()
	check("set_attenuation_both", r0 == 15 && r1 == 15)
	full.SetZeroCrossing(false)
	cfg, _ = full.GetConfig()
	check("zero_crossing_off", !cfg.ZeroCrossing)
	full.SetZeroCrossing(true)
	full.SetOption(2)
	cfg, _ = full.GetConfig()
	check("option2", cfg.Option == 2 && full.MaxPosition() == 32)
	full.SetBoth(13, 33)
	r0, r1, _, _ = full.ReadAll()
	check("option2_positions", r0 == 13 && r1 == 33 && full.PositionToDB(13) == 14)
	full.SetOption(1)
	cfg, _ = full.GetConfig()
	check("option1_again", cfg.Option == 1)
	full.Reset()
	r0, r1, _, _ = full.ReadAll()
	check("reset_mutes", r0 == 63 && r1 == 63)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
