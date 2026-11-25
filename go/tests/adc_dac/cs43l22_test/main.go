//go:build linux && !tinygo

// CS43L22 hardware test — Linux host.
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

	dac, err := adcdac.NewCS43L22Full(conn, nil)
	if err != nil {
		fail("new", err)
		os.Exit(2)
	}
	check("init", dac.Init() == nil)
	id, _, _ := dac.ReadID()
	check("read_id", id == 0x1C)
	dac.SetVolume(-20)
	v, _ := dac.GetVolume()
	check("volume_roundtrip", v > -20.01 && v < -19.99)
	dac.SetVolumeChannels(-10, -12)
	r20, _ := dac.ReadRegister(0x20)
	r21, _ := dac.ReadRegister(0x21)
	check("volume_channels", r20 == 0xEC && r21 == 0xE8)
	dac.SetHeadphoneGain(adcdac.CS43L22Gain1000)
	g, _ := dac.HeadphoneGain()
	check("headphone_gain", g == 1.0)
	dac.Mute(true)
	m, _ := dac.ReadRegister(0x0F)
	check("mute", m&0xC0 == 0xC0)
	dac.Mute(false)
	m, _ = dac.ReadRegister(0x0F)
	check("unmute", m&0xC0 == 0)
	dac.SetTone(6, 6, 0, 0, true)
	tone, _ := dac.ReadRegister(0x1F)
	check("tone", tone == 0x44)
	l := adcdac.DefaultCS43L22Limiter()
	l.Enabled, l.MaxDB, l.CushionDB = true, -6, -12
	dac.SetLimiter(l)
	lim, _ := dac.ReadRegister(0x28)
	check("limiter", lim&0x80 == 0x80)
	dac.SetLimiter(adcdac.DefaultCS43L22Limiter())
	_, err = dac.ReadSpeakerStatus()
	check("speaker_status_readable", err == nil)
	dac.Stop()
	pwr, _ := dac.ReadRegister(0x02)
	check("stop_powers_down", pwr == 0x9F)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
