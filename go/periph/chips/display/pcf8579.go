package display

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PCF8579 command prefixes. The chip has no register map: commands are single
// bytes whose MSB is the continuation bit C (1 = another command follows,
// 0 = last command, display data follows).
const (
	pcf8579CmdSetMode      uint8 = 0x40
	pcf8579CmdDeviceSelect uint8 = 0x60
	pcf8579CmdRAMAccess    uint8 = 0x70
	pcf8579CmdSetStartBank uint8 = 0x7C
	pcf8579Cont            uint8 = 0x80
)

// PCF8579 display status values for SetDisplay.
const (
	PCF8579DisplayBlank   uint8 = 0
	PCF8579DisplayNormal  uint8 = 1
	PCF8579DisplayAllOn   uint8 = 2
	PCF8579DisplayInverse uint8 = 3
)

// PCF8579 RAM access modes for WriteRAM / ReadRAM.
const (
	PCF8579AccessCharacter   uint8 = 0
	PCF8579AccessHalfGraphic uint8 = 1
	PCF8579AccessFullGraphic uint8 = 2
)

const (
	pcf8579RAMColumns = 40
	pcf8579RAMBanks   = 4
)

var pcf8579PerCol = [3]int{1, 2, 4}

// Errors returned by the PCF8579 driver.
var (
	// ErrPCF8579InvalidArgument is returned for out-of-range rows, banks, columns, modes, …
	ErrPCF8579InvalidArgument = errors.New("pcf8579: invalid argument")
)

// pcf8579MuxCode maps the row count to the set-mode M[1:0] field.
func pcf8579MuxCode(rows int) (uint8, bool) {
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

// PCF8579Minimal is the LCD column driver — minimal interface.
//
// The PCF8579 is the column-driver companion of the PCF8578 and cannot run
// alone: the clock, frame sync and row waveforms come from a PCF8578 (or an
// equivalent source). The driver writes the display RAM of one PCF8579
// (4 banks x 40 columns, X = n drives output Cn). The chip has no register
// map; the driver streams command bytes and display data with
// Connection.Write / WriteRead. A shadow copy of the RAM is kept so
// single-pixel updates need only one byte on the bus. One RAM byte is 8
// vertical pixels of one column; bit 0 is the top row of the bank.
//
// device-select, set-mode and set-start-bank reach every device on the I²C
// address, so all drivers on one bus must use the same rows / mixed settings.
//
// The I²C address is 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD); the chip supports
// 100 kHz at most.
type PCF8579Minimal struct {
	connection connection.Connection
	subaddress uint8
	rows       int
	mixed      bool
	status     uint8
	startBank  uint8
	shadow     [pcf8579RAMBanks][pcf8579RAMColumns]byte
}

// NewPCF8579Minimal creates a PCF8579Minimal: waits 2 ms for the internal
// power-on reset, clears this device's RAM, then switches the display on
// (normal video, start bank 0). A device whose subaddress pins A3:A0 do not
// match subaddress does not acknowledge the data bytes and the bus error is
// returned.
//
// subaddress is 0 … 15, rows must be 8, 16, 24 or 32, mixed tells whether the
// PCF8578 in the system runs in mixed mode (the T bit); connection must be
// bound to the chip's 7-bit address (100 kHz maximum).
func NewPCF8579Minimal(t connection.Connection, subaddress uint8, rows int, mixed bool) (*PCF8579Minimal, error) {
	if _, ok := pcf8579MuxCode(rows); !ok || subaddress > 15 {
		return nil, ErrPCF8579InvalidArgument
	}
	d := &PCF8579Minimal{connection: t, subaddress: subaddress, rows: rows, mixed: mixed, status: PCF8579DisplayNormal}
	time.Sleep(2 * time.Millisecond) // >= 1 ms for the internal power-on reset
	if err := d.doInit(); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *PCF8579Minimal) modeByte(status uint8, cont bool) uint8 {
	code, _ := pcf8579MuxCode(d.rows)
	b := pcf8579CmdSetMode | (status << 2) | code
	if d.mixed {
		b |= 1 << 4
	}
	if cont {
		b |= pcf8579Cont
	}
	return b
}

func pcf8579RAMAccessByte(access uint8, bank int) uint8 {
	return pcf8579Cont | pcf8579CmdRAMAccess | (access << 2) | uint8(bank)
}

func (d *PCF8579Minimal) selectByte() uint8 {
	return pcf8579Cont | pcf8579CmdDeviceSelect | d.subaddress
}

func (d *PCF8579Minimal) doInit() error {
	d.startBank = 0
	buf := make([]byte, 5+pcf8579RAMColumns*pcf8579RAMBanks)
	buf[0] = d.selectByte()
	buf[1] = d.modeByte(PCF8579DisplayBlank, true)
	buf[2] = pcf8579Cont | pcf8579CmdSetStartBank
	buf[3] = pcf8579RAMAccessByte(PCF8579AccessFullGraphic, 0)
	buf[4] = 0x00 // load-X 0 (C = 0); the 160 data bytes stay zero
	if err := d.connection.Write(buf); err != nil {
		return err
	}
	d.shadow = [pcf8579RAMBanks][pcf8579RAMColumns]byte{}
	d.status = PCF8579DisplayNormal
	return d.connection.Write([]byte{d.modeByte(PCF8579DisplayNormal, false)})
}

// ramWrite writes data at RAM column x / bank and mirrors it in the shadow.
func (d *PCF8579Minimal) ramWrite(x, bank int, data []byte, access uint8) error {
	buf := make([]byte, 0, 3+len(data))
	buf = append(buf, d.selectByte(), pcf8579RAMAccessByte(access, bank), byte(x&0x3F))
	buf = append(buf, data...)
	if err := d.connection.Write(buf); err != nil {
		return err
	}
	perCol := pcf8579PerCol[access]
	for i, b := range data {
		if col := x + i/perCol; col < pcf8579RAMColumns {
			d.shadow[bank+(i%perCol)][col] = b
		}
	}
	return nil
}

// Clear zeroes this device's whole display RAM (4 banks x 40 columns) and the shadow.
func (d *PCF8579Minimal) Clear() error {
	buf := make([]byte, 3+pcf8579RAMColumns*pcf8579RAMBanks)
	buf[0] = d.selectByte()
	buf[1] = pcf8579RAMAccessByte(PCF8579AccessFullGraphic, 0)
	buf[2] = 0x00
	if err := d.connection.Write(buf); err != nil {
		return err
	}
	d.shadow = [pcf8579RAMBanks][pcf8579RAMColumns]byte{}
	return nil
}

// SetPixel sets (on) or clears one pixel at column x (0 .. 39) and row y
// (0 .. rows-1). Out-of-range coordinates are ignored. It writes exactly one
// RAM byte.
func (d *PCF8579Minimal) SetPixel(x, y int, on bool) error {
	if x < 0 || x >= pcf8579RAMColumns || y < 0 || y >= d.rows {
		return nil
	}
	bank := y / 8
	bit := byte(1) << uint(y%8)
	v := d.shadow[bank][x]
	if on {
		v |= bit
	} else {
		v &^= bit
	}
	return d.ramWrite(x, bank, []byte{v}, PCF8579AccessCharacter)
}

// WriteColumns writes consecutive column bytes in character access mode at
// column x of bank bank (0 .. rows/8-1); bit 0 is the top row of the bank.
// The write is truncated at column 39.
func (d *PCF8579Minimal) WriteColumns(x, bank int, data []byte) error {
	if bank < 0 || bank >= d.rows/8 {
		return ErrPCF8579InvalidArgument
	}
	if x < 0 || x >= pcf8579RAMColumns {
		return nil
	}
	if len(data) > pcf8579RAMColumns-x {
		data = data[:pcf8579RAMColumns-x]
	}
	if len(data) == 0 {
		return nil
	}
	return d.ramWrite(x, bank, data, PCF8579AccessCharacter)
}

// PCF8579Full is the LCD column driver — full interface.
// Extends PCF8579Minimal with pixel read-back, fill, line/rectangle/bitmap
// drawing, display status, multiplex rate and PCF8578 mode bit, hardware
// vertical scrolling, and raw RAM access in all three access modes including
// transfers that run on into the following cascaded devices.
//
// Embeds PCF8579Minimal so all minimal methods are promoted onto the full
// driver automatically.
type PCF8579Full struct {
	*PCF8579Minimal
}

// NewPCF8579Full creates a PCF8579Full (see NewPCF8579Minimal).
func NewPCF8579Full(t connection.Connection, subaddress uint8, rows int, mixed bool) (*PCF8579Full, error) {
	m, err := NewPCF8579Minimal(t, subaddress, rows, mixed)
	if err != nil {
		return nil, err
	}
	return &PCF8579Full{PCF8579Minimal: m}, nil
}

// pcf8579Dirty is a per-bank bitmask of changed RAM columns (bit n = column n).
type pcf8579Dirty [pcf8579RAMBanks]uint64

func (d *PCF8579Full) plot(x, y int, on bool, dirty *pcf8579Dirty) {
	if x < 0 || x >= pcf8579RAMColumns || y < 0 || y >= d.rows {
		return
	}
	bank := y / 8
	bit := byte(1) << uint(y%8)
	if on {
		d.shadow[bank][x] |= bit
	} else {
		d.shadow[bank][x] &^= bit
	}
	dirty[bank] |= 1 << uint(x)
}

// flush sends one transaction per run of consecutive changed columns in a bank.
func (d *PCF8579Full) flush(dirty *pcf8579Dirty) error {
	for bank := 0; bank < pcf8579RAMBanks; bank++ {
		x := 0
		for x < pcf8579RAMColumns {
			if dirty[bank]&(1<<uint(x)) == 0 {
				x++
				continue
			}
			start := x
			for x < pcf8579RAMColumns && dirty[bank]&(1<<uint(x)) != 0 {
				x++
			}
			run := append([]byte(nil), d.shadow[bank][start:x]...)
			if err := d.ramWrite(start, bank, run, PCF8579AccessCharacter); err != nil {
				return err
			}
		}
	}
	return nil
}

// GetPixel returns the state of one pixel from the shadow (no bus traffic);
// out-of-range coordinates read as false.
func (d *PCF8579Full) GetPixel(x, y int) bool {
	if x < 0 || x >= pcf8579RAMColumns || y < 0 || y >= d.rows {
		return false
	}
	return d.shadow[y/8][x]&(1<<uint(y%8)) != 0
}

// Fill sets every displayed pixel (rows x 40) on or off.
func (d *PCF8579Full) Fill(on bool) error {
	buf := make([]byte, pcf8579RAMColumns)
	if on {
		for i := range buf {
			buf[i] = 0xFF
		}
	}
	for bank := 0; bank < d.rows/8; bank++ {
		if err := d.ramWrite(0, bank, buf, PCF8579AccessCharacter); err != nil {
			return err
		}
	}
	return nil
}

func pcf8579Abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// DrawLine draws a Bresenham line, written as one batch of changed bytes.
func (d *PCF8579Full) DrawLine(x0, y0, x1, y1 int, on bool) error {
	var dirty pcf8579Dirty
	dx := pcf8579Abs(x1 - x0)
	dy := -pcf8579Abs(y1 - y0)
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
func (d *PCF8579Full) DrawRect(x, y, w, h int, on, fill bool) error {
	if w <= 0 || h <= 0 {
		return nil
	}
	var dirty pcf8579Dirty
	for yy := y; yy < y+h; yy++ {
		for xx := x; xx < x+w; xx++ {
			if fill || yy == y || yy == y+h-1 || xx == x || xx == x+w-1 {
				d.plot(xx, yy, on, &dirty)
			}
		}
	}
	return d.flush(&dirty)
}

// ShowBitmap blits 8-pixel-high column bytes (bit 0 = top row) aligned to a
// bank boundary: y must be a non-negative multiple of 8. Content past column
// 39 is clipped.
func (d *PCF8579Full) ShowBitmap(x, y int, columns []byte) error {
	if y < 0 || y%8 != 0 {
		return ErrPCF8579InvalidArgument
	}
	return d.WriteColumns(x, y/8, columns)
}

// SetDisplay sets the display status (PCF8579DisplayBlank … PCF8579DisplayInverse);
// RAM contents are preserved.
func (d *PCF8579Full) SetDisplay(status uint8) error {
	if status > 3 {
		return ErrPCF8579InvalidArgument
	}
	d.status = status
	return d.connection.Write([]byte{d.modeByte(status, false)})
}

// SetMode changes the multiplex rate (rows 8, 16, 24 or 32) and the PCF8578
// mode bit T (mixed); it affects all devices on the address.
func (d *PCF8579Full) SetMode(rows int, mixed bool) error {
	if _, ok := pcf8579MuxCode(rows); !ok {
		return ErrPCF8579InvalidArgument
	}
	d.rows = rows
	d.mixed = mixed
	return d.connection.Write([]byte{d.modeByte(d.status, false)})
}

// SetStartBank selects the RAM bank (0 … 3) shown at the top of the display
// (hardware vertical scroll).
func (d *PCF8579Full) SetStartBank(bank uint8) error {
	if bank > 3 {
		return ErrPCF8579InvalidArgument
	}
	d.startBank = bank
	return d.connection.Write([]byte{pcf8579CmdSetStartBank | bank})
}

// Scroll adds banks (may be negative) to the start bank, modulo the number of
// banks in use.
func (d *PCF8579Full) Scroll(banks int) error {
	n := d.rows / 8
	b := ((int(d.startBank)+banks)%n + n) % n
	return d.SetStartBank(uint8(b))
}

func pcf8579CheckRAMArgs(x, bank, count int, access uint8, cascade bool) error {
	if access > 2 || x < 0 || x > 39 || bank < 0 || bank > 3 {
		return ErrPCF8579InvalidArgument
	}
	if (access == PCF8579AccessHalfGraphic && bank%2 != 0) || (access == PCF8579AccessFullGraphic && bank != 0) {
		return ErrPCF8579InvalidArgument
	}
	perCol := pcf8579PerCol[access]
	if !cascade && x+(count+perCol-1)/perCol > pcf8579RAMColumns {
		return ErrPCF8579InvalidArgument
	}
	return nil
}

// WriteRAM writes raw bytes to this device at RAM column x (0 … 39) and bank (0 … 3) in
// character, half-graphic or full-graphic order (access). Half-graphic must
// start at an even bank and full-graphic at bank 0; writes that would pass
// column 39 are rejected.
func (d *PCF8579Full) WriteRAM(x, bank int, data []byte, access uint8) error {
	if err := pcf8579CheckRAMArgs(x, bank, len(data), access, false); err != nil {
		return err
	}
	if len(data) == 0 {
		return nil
	}
	return d.ramWrite(x, bank, data, access)
}

// WriteRAMCascade writes from column 0, bank 0 of this device and lets the
// data run on into the following subaddresses (the chip advances its
// subaddress counter after column 39). The shadow only tracks this device's
// first 40 columns.
func (d *PCF8579Full) WriteRAMCascade(data []byte, access uint8) error {
	if err := pcf8579CheckRAMArgs(0, 0, len(data), access, true); err != nil {
		return err
	}
	if len(data) == 0 {
		return nil
	}
	return d.ramWrite(0, 0, data, access)
}

// ReadRAM reads n raw bytes: the address-set commands, a repeated start, then
// the read.
func (d *PCF8579Full) ReadRAM(x, bank, n int, access uint8) ([]byte, error) {
	if err := pcf8579CheckRAMArgs(x, bank, n, access, false); err != nil {
		return nil, err
	}
	cmds := []byte{
		d.selectByte(),
		pcf8579RAMAccessByte(access, bank),
		byte(x & 0x3F),
	}
	return d.connection.WriteRead(cmds, n)
}

// Reset re-runs initialisation (clears this device's RAM, normal video, start
// bank 0).
func (d *PCF8579Full) Reset() error {
	return d.doInit()
}
