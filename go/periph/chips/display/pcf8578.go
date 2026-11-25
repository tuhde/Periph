package display

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCF8578 command prefixes. The chip has no register map: commands are single
// bytes whose MSB is the continuation bit C (1 = another command follows,
// 0 = last command, display data follows).
const (
	pcf8578CmdSetMode      uint8 = 0x40
	pcf8578CmdDeviceSelect uint8 = 0x60
	pcf8578CmdRAMAccess    uint8 = 0x70
	pcf8578CmdSetStartBank uint8 = 0x7C
	pcf8578Cont            uint8 = 0x80
)

// PCF8578 display status values for SetDisplay.
const (
	PCF8578DisplayBlank   uint8 = 0
	PCF8578DisplayNormal  uint8 = 1
	PCF8578DisplayAllOn   uint8 = 2
	PCF8578DisplayInverse uint8 = 3
)

// PCF8578 RAM access modes for WriteRAM / ReadRAM.
const (
	PCF8578AccessCharacter   uint8 = 0
	PCF8578AccessHalfGraphic uint8 = 1
	PCF8578AccessFullGraphic uint8 = 2
)

const (
	pcf8578RAMColumns = 40
	pcf8578RAMBanks   = 4
)

var pcf8578PerCol = [3]int{1, 2, 4}

// Errors returned by the PCF8578 driver.
var (
	// ErrPCF8578InvalidArgument is returned for out-of-range rows, banks, columns, modes, …
	ErrPCF8578InvalidArgument = errors.New("pcf8578: invalid argument")
	// ErrPCF8578RowMode is returned by RAM access while row mode is selected.
	ErrPCF8578RowMode = errors.New("pcf8578: RAM is only accessible in mixed mode")
)

// pcf8578MuxCode maps the row count to the set-mode M[1:0] field.
func pcf8578MuxCode(rows int) (uint8, bool) {
	switch rows {
	case 8:
		return 1, true
	case 16:
		return 2, true
	case 24:
		return 3, true
	case 32:
		return 0, true
	}
	return 0, false
}

// PCF8578Minimal is the row/column LCD dot-matrix driver — minimal interface.
//
// Drives a stand-alone dot-matrix LCD (mixed mode: the chip drives both rows
// and columns). With rows rows the panel is 40-rows columns wide (8x32, 16x24,
// 24x16 or 32x8). The chip has no register map; the driver streams command
// bytes and display data with Connection.Write / WriteRead. A shadow copy of
// the 4 x 40 byte display RAM is kept so single-pixel updates need only one
// byte on the bus. One RAM byte is 8 vertical pixels of one column; bit 0 is
// the top row of the bank.
//
// The I²C address is 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD); the chip supports
// 100 kHz at most.
type PCF8578Minimal struct {
	connection connection.Connection
	rows       int
	mixed      bool
	status     uint8
	startBank  uint8
	shadow     [pcf8578RAMBanks][pcf8578RAMColumns]byte
}

// NewPCF8578Minimal creates a PCF8578Minimal: waits 2 ms for the internal
// power-on reset, clears the whole RAM, then switches the display on (normal
// video, start bank 0).
//
// rows must be 8, 16, 24 or 32; connection must be bound to the chip's 7-bit
// address (100 kHz maximum).
func NewPCF8578Minimal(t connection.Connection, rows int) (*PCF8578Minimal, error) {
	if _, ok := pcf8578MuxCode(rows); !ok {
		return nil, ErrPCF8578InvalidArgument
	}
	d := &PCF8578Minimal{connection: t, rows: rows, mixed: true, status: PCF8578DisplayNormal}
	time.Sleep(2 * time.Millisecond) // >= 1 ms for the internal power-on reset
	if err := d.doInit(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *PCF8578Minimal) modeByte(status uint8, cont bool) uint8 {
	code, _ := pcf8578MuxCode(d.rows)
	b := pcf8578CmdSetMode | (status << 2) | code
	if d.mixed {
		b |= 1 << 4
	}
	if cont {
		b |= pcf8578Cont
	}
	return b
}

func pcf8578RAMAccessByte(access uint8, bank int) uint8 {
	return pcf8578Cont | pcf8578CmdRAMAccess | (access << 2) | uint8(bank)
}

func (d *PCF8578Minimal) doInit() error {
	d.mixed = true
	d.startBank = 0
	buf := make([]byte, 5+pcf8578RAMColumns*pcf8578RAMBanks)
	buf[0] = pcf8578Cont | pcf8578CmdDeviceSelect
	buf[1] = d.modeByte(PCF8578DisplayBlank, true)
	buf[2] = pcf8578Cont | pcf8578CmdSetStartBank
	buf[3] = pcf8578RAMAccessByte(PCF8578AccessFullGraphic, 0)
	buf[4] = 0x00 // load-X 0 (C = 0); the 160 data bytes stay zero
	if err := d.connection.Write(buf); err != nil {
		return err
	}
	d.shadow = [pcf8578RAMBanks][pcf8578RAMColumns]byte{}
	d.status = PCF8578DisplayNormal
	return d.connection.Write([]byte{d.modeByte(PCF8578DisplayNormal, false)})
}

func (d *PCF8578Minimal) requireMixed() error {
	if !d.mixed {
		return ErrPCF8578RowMode
	}
	return nil
}

// ramWrite writes data at RAM column x / bank and mirrors it in the shadow.
func (d *PCF8578Minimal) ramWrite(x, bank int, data []byte, access uint8) error {
	if err := d.requireMixed(); err != nil {
		return err
	}
	buf := make([]byte, 0, 3+len(data))
	buf = append(buf, pcf8578Cont|pcf8578CmdDeviceSelect, pcf8578RAMAccessByte(access, bank), byte(x&0x3F))
	buf = append(buf, data...)
	if err := d.connection.Write(buf); err != nil {
		return err
	}
	perCol := pcf8578PerCol[access]
	for i, b := range data {
		d.shadow[bank+(i%perCol)][x+i/perCol] = b
	}
	return nil
}

// Clear zeroes the whole display RAM (4 banks x 40 columns) and the shadow.
func (d *PCF8578Minimal) Clear() error {
	if err := d.requireMixed(); err != nil {
		return err
	}
	buf := make([]byte, 3+pcf8578RAMColumns*pcf8578RAMBanks)
	buf[0] = pcf8578Cont | pcf8578CmdDeviceSelect
	buf[1] = pcf8578RAMAccessByte(PCF8578AccessFullGraphic, 0)
	buf[2] = 0x00
	if err := d.connection.Write(buf); err != nil {
		return err
	}
	d.shadow = [pcf8578RAMBanks][pcf8578RAMColumns]byte{}
	return nil
}

// SetPixel sets (on) or clears one pixel at display column x (0 .. 40-rows-1)
// and row y (0 .. rows-1). Out-of-range coordinates are ignored. It writes
// exactly one RAM byte.
func (d *PCF8578Minimal) SetPixel(x, y int, on bool) error {
	if err := d.requireMixed(); err != nil {
		return err
	}
	if x < 0 || x >= pcf8578RAMColumns-d.rows || y < 0 || y >= d.rows {
		return nil
	}
	ramX := d.rows + x
	bank := y / 8
	bit := byte(1) << uint(y%8)
	v := d.shadow[bank][ramX]
	if on {
		v |= bit
	} else {
		v &^= bit
	}
	return d.ramWrite(ramX, bank, []byte{v}, PCF8578AccessCharacter)
}

// WriteColumns writes consecutive column bytes in character access mode at
// display column x of display bank bank (0 .. rows/8-1); bit 0 is the top row
// of the bank. The write is truncated at the last display column.
func (d *PCF8578Minimal) WriteColumns(x, bank int, data []byte) error {
	if bank < 0 || bank >= d.rows/8 {
		return ErrPCF8578InvalidArgument
	}
	columns := pcf8578RAMColumns - d.rows
	if x < 0 || x >= columns {
		return nil
	}
	if len(data) > columns-x {
		data = data[:columns-x]
	}
	if len(data) == 0 {
		return nil
	}
	return d.ramWrite(d.rows+x, bank, data, PCF8578AccessCharacter)
}

// PCF8578Full is the row/column LCD dot-matrix driver — full interface.
// Extends PCF8578Minimal with pixel read-back, fill, line/rectangle/bitmap
// drawing, display status, multiplex rate and row mode, hardware vertical
// scrolling, raw RAM access in all three access modes and the device-select
// command for PCF8579 cascades.
//
// Embeds PCF8578Minimal so all minimal methods are promoted onto the full
// driver automatically.
type PCF8578Full struct {
	*PCF8578Minimal
}

// NewPCF8578Full creates a PCF8578Full (see NewPCF8578Minimal).
func NewPCF8578Full(t connection.Connection, rows int) (*PCF8578Full, error) {
	m, err := NewPCF8578Minimal(t, rows)
	if err != nil {
		return nil, err
	}
	return &PCF8578Full{PCF8578Minimal: m}, nil
}

// pcf8578Dirty is a per-bank bitmask of changed RAM columns (bit n = column n).
type pcf8578Dirty [pcf8578RAMBanks]uint64

func (d *PCF8578Full) plot(x, y int, on bool, dirty *pcf8578Dirty) {
	if x < 0 || x >= pcf8578RAMColumns-d.rows || y < 0 || y >= d.rows {
		return
	}
	ramX := d.rows + x
	bank := y / 8
	bit := byte(1) << uint(y%8)
	if on {
		d.shadow[bank][ramX] |= bit
	} else {
		d.shadow[bank][ramX] &^= bit
	}
	dirty[bank] |= 1 << uint(ramX)
}

// flush sends one transaction per run of consecutive changed columns in a bank.
func (d *PCF8578Full) flush(dirty *pcf8578Dirty) error {
	for bank := 0; bank < pcf8578RAMBanks; bank++ {
		x := 0
		for x < pcf8578RAMColumns {
			if dirty[bank]&(1<<uint(x)) == 0 {
				x++
				continue
			}
			start := x
			for x < pcf8578RAMColumns && dirty[bank]&(1<<uint(x)) != 0 {
				x++
			}
			run := append([]byte(nil), d.shadow[bank][start:x]...)
			if err := d.ramWrite(start, bank, run, PCF8578AccessCharacter); err != nil {
				return err
			}
		}
	}
	return nil
}

// GetPixel returns the state of one pixel from the shadow (no bus traffic);
// out-of-range coordinates read as false.
func (d *PCF8578Full) GetPixel(x, y int) bool {
	if x < 0 || x >= pcf8578RAMColumns-d.rows || y < 0 || y >= d.rows {
		return false
	}
	return d.shadow[y/8][d.rows+x]&(1<<uint(y%8)) != 0
}

// Fill sets every displayed pixel on or off.
func (d *PCF8578Full) Fill(on bool) error {
	columns := pcf8578RAMColumns - d.rows
	buf := make([]byte, columns)
	if on {
		for i := range buf {
			buf[i] = 0xFF
		}
	}
	for bank := 0; bank < d.rows/8; bank++ {
		if err := d.ramWrite(d.rows, bank, buf, PCF8578AccessCharacter); err != nil {
			return err
		}
	}
	return nil
}

func pcf8578Abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// DrawLine draws a Bresenham line, written as one batch of changed bytes.
func (d *PCF8578Full) DrawLine(x0, y0, x1, y1 int, on bool) error {
	if err := d.requireMixed(); err != nil {
		return err
	}
	var dirty pcf8578Dirty
	dx := pcf8578Abs(x1 - x0)
	dy := -pcf8578Abs(y1 - y0)
	sx, sy := -1, -1
	if x0 < x1 {
		sx = 1
	}
	if y0 < y1 {
		sy = 1
	}
	err := dx + dy
	for {
		d.plot(x0, y0, on, &dirty)
		if x0 == x1 && y0 == y1 {
			break
		}
		e2 := 2 * err
		if e2 >= dy {
			err += dy
			x0 += sx
		}
		if e2 <= dx {
			err += dx
			y0 += sy
		}
	}
	return d.flush(&dirty)
}

// DrawRect draws a rectangle outline, or a filled rectangle when fill is true.
func (d *PCF8578Full) DrawRect(x, y, w, h int, on, fill bool) error {
	if err := d.requireMixed(); err != nil {
		return err
	}
	if w <= 0 || h <= 0 {
		return nil
	}
	var dirty pcf8578Dirty
	for yy := y; yy < y+h; yy++ {
		for xx := x; xx < x+w; xx++ {
			if fill || yy == y || yy == y+h-1 || xx == x || xx == x+w-1 {
				d.plot(xx, yy, on, &dirty)
			}
		}
	}
	return d.flush(&dirty)
}

// ShowBitmap blits a column-major bitmap aligned to a bank boundary. y must be
// a multiple of 8. bands holds one byte slice per 8-pixel-high band from the
// top, each with one byte per column (bit 0 = top row); content outside the
// display is clipped.
func (d *PCF8578Full) ShowBitmap(x, y int, bands [][]byte) error {
	if y < 0 || y%8 != 0 {
		return ErrPCF8578InvalidArgument
	}
	for i, band := range bands {
		bank := y/8 + i
		if bank >= d.rows/8 {
			break
		}
		if err := d.WriteColumns(x, bank, band); err != nil {
			return err
		}
	}
	return nil
}

// SetDisplay sets the display status (PCF8578DisplayBlank … PCF8578DisplayInverse);
// RAM contents are preserved.
func (d *PCF8578Full) SetDisplay(status uint8) error {
	if status > 3 {
		return ErrPCF8578InvalidArgument
	}
	d.status = status
	return d.connection.Write([]byte{d.modeByte(status, false)})
}

// SetMode changes the multiplex rate (rows 8, 16, 24 or 32) and the row/mixed
// mode. In row mode (mixed false) the RAM cannot be accessed; pixel and RAM
// calls return ErrPCF8578RowMode until mixed mode is restored.
func (d *PCF8578Full) SetMode(rows int, mixed bool) error {
	if _, ok := pcf8578MuxCode(rows); !ok {
		return ErrPCF8578InvalidArgument
	}
	d.rows = rows
	d.mixed = mixed
	return d.connection.Write([]byte{d.modeByte(d.status, false)})
}

// SetStartBank selects the RAM bank (0 … 3) shown at the top of the display
// (hardware vertical scroll).
func (d *PCF8578Full) SetStartBank(bank uint8) error {
	if bank > 3 {
		return ErrPCF8578InvalidArgument
	}
	d.startBank = bank
	return d.connection.Write([]byte{pcf8578CmdSetStartBank | bank})
}

// Scroll adds banks (may be negative) to the start bank, modulo the number of
// banks in use.
func (d *PCF8578Full) Scroll(banks int) error {
	n := d.rows / 8
	b := ((int(d.startBank)+banks)%n + n) % n
	return d.SetStartBank(uint8(b))
}

func pcf8578CheckRAMArgs(x, bank, count int, access uint8) error {
	if access > 2 || x < 0 || x > 39 || bank < 0 || bank > 3 {
		return ErrPCF8578InvalidArgument
	}
	if (access == PCF8578AccessHalfGraphic && bank%2 != 0) || (access == PCF8578AccessFullGraphic && bank != 0) {
		return ErrPCF8578InvalidArgument
	}
	perCol := pcf8578PerCol[access]
	if x+(count+perCol-1)/perCol > pcf8578RAMColumns {
		return ErrPCF8578InvalidArgument
	}
	return nil
}

// WriteRAM writes raw bytes at RAM column x (0 … 39) and bank (0 … 3) in
// character, half-graphic or full-graphic order (access). Half-graphic must
// start at an even bank and full-graphic at bank 0; writes that would pass
// column 39 are rejected.
func (d *PCF8578Full) WriteRAM(x, bank int, data []byte, access uint8) error {
	if err := pcf8578CheckRAMArgs(x, bank, len(data), access); err != nil {
		return err
	}
	if len(data) == 0 {
		return nil
	}
	return d.ramWrite(x, bank, data, access)
}

// ReadRAM reads n raw bytes: the address-set commands, a repeated start, then
// the read.
func (d *PCF8578Full) ReadRAM(x, bank, n int, access uint8) ([]byte, error) {
	if err := d.requireMixed(); err != nil {
		return nil, err
	}
	if err := pcf8578CheckRAMArgs(x, bank, n, access); err != nil {
		return nil, err
	}
	cmds := []byte{
		pcf8578Cont | pcf8578CmdDeviceSelect,
		pcf8578RAMAccessByte(access, bank),
		byte(x & 0x3F),
	}
	return d.connection.WriteRead(cmds, n)
}

// DeviceSelect loads the subaddress counter (0 … 15) for PCF8579 cascades.
// Subaddress 0 addresses the PCF8578 itself; pixel and RAM calls restore it
// before their own accesses.
func (d *PCF8578Full) DeviceSelect(subaddress uint8) error {
	if subaddress > 15 {
		return ErrPCF8578InvalidArgument
	}
	return d.connection.Write([]byte{pcf8578CmdDeviceSelect | subaddress})
}

// Reset re-runs initialisation at the current row count (clears RAM, mixed
// mode, normal video).
func (d *PCF8578Full) Reset() error {
	return d.doInit()
}
