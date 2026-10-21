//go:build tinygo

// 23X1024 hardware test — TinyGo / Raspberry Pi Pico W smoke test.
package main

import (
	"bytes"
	"machine"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 8_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			println("PASS", label)
			passed++
		} else {
			println("FAIL", label)
			failed++
		}
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	chip, err := memory.NewSRAM23X1024Full(conn)                     // Create 23X1024 Full driver, (connection) → (*SRAM23X1024Full, error)
	check("init_mode_roundtrip", err == nil)                         // constructor writes MODE=0x40 and verifies it with RDMR
	if err != nil {
		println("===DONE: " + strconv.Itoa(passed) + " passed, " + strconv.Itoa(failed) + " failed===")
		return
	}
	check("size", chip.Size() == 131072)
	m, err := chip.GetMode()
	check("default_mode_sequential", err == nil && m == memory.SRAM23X1024ModeSequential)

	check("write_first", chip.WriteByte(0x00000, 0xA5) == nil)
	v, err := chip.ReadByte(0x00000)
	check("byte_roundtrip_first", err == nil && v == 0xA5)
	check("write_last", chip.WriteByte(0x1FFFF, 0x5A) == nil)
	v, err = chip.ReadByte(0x1FFFF)
	check("byte_roundtrip_last", err == nil && v == 0x5A)
	check("write_bit16", chip.WriteByte(0x10000, 0x3C) == nil)
	v1, _ := chip.ReadByte(0x10000)
	v0, _ := chip.ReadByte(0x00000)
	check("address_bit16_distinct", v1 == 0x3C && v0 == 0xA5)

	pattern := make([]byte, 1000)
	for i := range pattern {
		pattern[i] = byte(i*7 + 3)
	}
	check("write_block", chip.Write(0x00400, pattern) == nil)
	back, err := chip.Read(0x00400, 1000)
	check("block_roundtrip_chunked", err == nil && bytes.Equal(back, pattern))

	check("fill", chip.Fill(0x02000, 100, 0xC3) == nil)
	filled, _ := chip.Read(0x02000, 100)
	after, _ := chip.ReadByte(0x02064)
	check("fill_range", bytes.Equal(filled, bytes.Repeat([]byte{0xC3}, 100)) && after != 0xC3)

	check("write_end", chip.Write(0x1FFF0, pattern[:16]) == nil)
	tail, _ := chip.Read(0x1FFF0, 16)
	check("block_at_array_end", bytes.Equal(tail, pattern[:16]))

	check("set_mode_page_ok", chip.SetMode(memory.SRAM23X1024ModePage) == nil)
	m, _ = chip.GetMode()
	check("set_mode_page", m == memory.SRAM23X1024ModePage)
	check("write_page_crossing", chip.Write(0x04010, pattern[:64]) == nil) // crosses the pages at 0x4020 and 0x4040
	paged, _ := chip.Read(0x04010, 64)
	check("page_mode_crossing_linear", bytes.Equal(paged, pattern[:64]))

	check("set_mode_byte_ok", chip.SetMode(memory.SRAM23X1024ModeByte) == nil)
	m, _ = chip.GetMode()
	check("set_mode_byte", m == memory.SRAM23X1024ModeByte)
	check("write_byte_mode", chip.Write(0x05000, pattern[:8]) == nil)
	b8, _ := chip.Read(0x05000, 8)
	check("byte_mode_block", bytes.Equal(b8, pattern[:8]))

	check("set_mode_sequential_ok", chip.SetMode(memory.SRAM23X1024ModeSequential) == nil)
	m, _ = chip.GetMode()
	check("set_mode_sequential", m == memory.SRAM23X1024ModeSequential)

	check("reset_io", chip.ResetIO() == nil)
	m, _ = chip.GetMode()
	check("reset_io_stays_spi", m == memory.SRAM23X1024ModeSequential)
	v, _ = chip.ReadByte(0x00000)
	check("data_survives_reset_io", v == 0xA5)

	println("===DONE: " + strconv.Itoa(passed) + " passed, " + strconv.Itoa(failed) + " failed===")
}
