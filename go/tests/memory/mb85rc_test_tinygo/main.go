//go:build tinygo

// MB85RC hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MB85RC256V on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"bytes"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/memory"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	const ADDR = 0x50
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

	conn := connection.NewI2CConnectionWithWidth(i2c, ADDR, 2, nil, nil)
	chip, err := memory.NewMB85RCFull(conn)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	idConn := connection.NewI2CConnection(i2c, 0x7C, nil, nil)

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
	report := func(label string, err error) {
		if err != nil {
			fmt.Printf("%s: %v\n", label, err)
		}
	}

	check("size", chip.Size() == 32768)

	report("write_byte", chip.WriteUserByte(0x0010, 0x5A))
	b, err := chip.ReadUserByte(0x0010)
	report("read_byte", err)
	check("write_byte_read_byte_round_trip", b == 0x5A)

	data := make([]byte, 100)
	for i := range data {
		data[i] = byte(i)
	}
	report("write", chip.Write(0x0100, data))
	got, err := chip.Read(0x0100, len(data))
	report("read", err)
	check("chunked_write_read_round_trip", bytes.Equal(got, data))

	report("fill", chip.Fill(0x0100, 100, 0xC3))
	got, err = chip.Read(0x0100, 100)
	report("read fill", err)
	check("fill", bytes.Equal(got, bytes.Repeat([]byte{0xC3}, 100)))
	report("fill zero", chip.Fill(0x0100, 100, 0))
	got, err = chip.Read(0x0100, 100)
	report("read fill zero", err)
	check("fill_zero", bytes.Equal(got, make([]byte, 100)))

	report("write end", chip.Write(0x7FFE, []byte{0x12, 0x34}))
	got, err = chip.Read(0x7FFE, 2)
	report("read end", err)
	check("last_bytes_round_trip", bytes.Equal(got, []byte{0x12, 0x34}))

	_, err = chip.Read(0x7FFF, 2)
	check("wrap_rejected", err != nil)

	m, d, p, err := chip.ReadDeviceID(idConn, ADDR)
	if err != nil {
		fmt.Println("SKIP read_device_id (0x7C not accessible)")
	} else {
		check("read_device_id", m == 0x00A && d == 0x5 && p == 0x10)
	}

	time.Sleep(50 * time.Millisecond)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
