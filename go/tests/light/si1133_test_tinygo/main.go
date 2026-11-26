//go:build tinygo

// Si1133 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an Si1133 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	conn := connection.NewI2CConnection(i2c, 0x55, nil, nil)
	chip, _ := light.NewSi1133Full(conn)

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	check("init", chip.Init() == nil)
	part, hw, _, err := chip.ReadID()
	check("part_id", err == nil && part == 0x33)
	check("hw_id_impl", hw&0x1F == 0x03)
	resp, err := chip.ReadResponse()
	check("response_no_error", err == nil && !resp.Error)
	m, err := chip.Read()
	check("read_uv_range", err == nil && m.UV >= -0x800000 && m.UV <= 0x7FFFFF)
	check("read_white_positive_in_light", m.White > 0)
	_, err = chip.ReadUV()
	check("read_uv_scratch", err == nil)
	w, err := chip.ReadWhite()
	check("read_white_scratch", err == nil && w > 0)
	_, err = chip.ReadIR(light.Si1133MuxSmallIR)
	check("read_ir_small", err == nil)
	_, err = chip.ReadIR(light.Si1133MuxLargeIR)
	check("read_ir_large", err == nil)
	_ = chip.ConfigureChannel(2, light.Si1133MuxLargeIR, 5, 0, 0, false, false, 0)
	_ = chip.EnableChannels(0x07)
	v, err := chip.ReadChannels(true)
	check("read_channels_count", err == nil && len(v) == 3)
	check("read_channels_16bit", len(v) == 3 && v[2] >= 0 && v[2] <= 0xFFFF)
	_ = chip.WriteParameter(0x1C, 0x05)
	p, err := chip.ReadParameter(0x1C)
	check("parameter_roundtrip", err == nil && p == 0x05)
	p, err = chip.ReadParameter(0x01)
	check("chan_list_readback", err == nil && p == 0x07)
	_ = chip.SetThreshold(0, 1000)
	_ = chip.SetChannelThreshold(0, 1)
	hi, _ := chip.ReadParameter(0x25)
	lo, _ := chip.ReadParameter(0x26)
	check("threshold_readback", hi == 0x03 && lo == 0xE8)
	_ = chip.SetChannelThreshold(0, 0)
	_ = chip.EnableChannels(0x03)
	_, _ = chip.DataReady()
	_ = chip.StartAutonomous(0.1, [3]uint8{1, 1, 1})
	time.Sleep(500 * time.Millisecond)
	irq, err := chip.PollInterrupt()
	check("autonomous_irq", err == nil && irq&0x03 != 0)
	v, err = chip.ReadChannels(false)
	check("autonomous_values", err == nil && len(v) == 2)
	_ = chip.Pause()
	check("reset", chip.Reset() == nil)
	resp, err = chip.ReadResponse()
	check("reset_sleep", err == nil && resp.State&light.Si1133RespSleep != 0)
	m, err = chip.Read()
	check("read_after_reset", err == nil && m.White > 0)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
