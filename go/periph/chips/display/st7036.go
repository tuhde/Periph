package display

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ST7036 geometry of the DOGM162 (the Minimal default).
const (
	ST7036Columns = 16
	ST7036Rows    = 2
)

// ST7036Supply selects the supply voltage of the module (and with it the booster preset).
type ST7036Supply int

const (
	// ST7036Supply3V3 is 3.3 V with the booster on (needs the two booster capacitors).
	ST7036Supply3V3 ST7036Supply = iota
	// ST7036Supply5V is 5 V with the booster off.
	ST7036Supply5V
)

// ErrST7036Lines is returned by NewST7036Full for a line count other than 1, 2 or 3.
var ErrST7036Lines = errors.New("st7036: lines must be 1, 2 or 3")

const (
	st7036CmdClear    uint8 = 0x01
	st7036CmdHome     uint8 = 0x02
	st7036CmdEntry    uint8 = 0x04
	st7036CmdDisplay  uint8 = 0x08
	st7036CmdShift    uint8 = 0x10
	st7036CmdFunction uint8 = 0x20
	st7036CmdCGRAM    uint8 = 0x40
	st7036CmdDDRAM    uint8 = 0x80
	st7036CmdBias     uint8 = 0x14 // table 1: 0x14 | BS << 3 | FX
	st7036CmdIconAddr uint8 = 0x40 // table 1
	st7036CmdPower    uint8 = 0x50 // table 1: | Ion << 3 | Bon << 2 | C5 << 1 | C4
	st7036CmdFollower uint8 = 0x60 // table 1: | Fon << 3 | Rab2..0
	st7036CmdContrast uint8 = 0x70 // table 1: | C3..C0
	st7036CmdDHPos    uint8 = 0x10 // table 2: | UD << 3
)

// st7036Presets is {function, bias, power, follower, contrast low} per module
// (1, 2, 3 lines) and supply (3.3 V, 5 V), data sheet page 7.
var st7036Presets = [3][2][5]uint8{
	{{0x31, 0x14, 0x55, 0x6D, 0x7C}, {0x31, 0x1C, 0x51, 0x6A, 0x74}}, // DOGM081
	{{0x39, 0x14, 0x55, 0x6D, 0x78}, {0x39, 0x1C, 0x52, 0x69, 0x74}}, // DOGM162
	{{0x39, 0x15, 0x55, 0x6E, 0x72}, {0x39, 0x1D, 0x50, 0x6C, 0x7C}}, // DOGM163
}

// ST7036Minimal drives an ST7036 character LCD controller on an EA DOGM162W-A
// (2 x 16, 3.3 V) over 4-wire serial: SI, SCL, CSB, RS (C/D) and RESET. The
// HD44780-compatible instruction set has an extension table (selected by
// Function Set IS[2:1]) for bias, booster, follower and contrast; the DOG
// presets from the data sheet (page 7) are sent at initialisation.
//
// The connection is a connection.DisplayConn with no BUSY pin: the serial
// mode is write-only and has no busy flag, so every instruction is followed by
// a fixed wait (30 us, 2 ms after Clear / Return Home). Every instruction is a
// single byte sent with RS (D/C#) low; every data byte gets its own CS window,
// followed by the 30 us gap (DelayMicros). SPI mode 0 at about 1 MHz or less
// (SCL cycle >= 200 ns at <= 4.5 V).
type ST7036Minimal struct {
	conn     connection.DisplayConn
	lines    int
	columns  int
	n        uint8 // Function Set N
	dh       uint8 // Function Set DH
	bon      uint8 // booster on
	ion      uint8 // ICON display on
	contrast uint8 // C5..C0
	fon      uint8 // follower on
	rab      uint8 // Rab2..0
	quarter  bool  // bias 1/4
	fx       uint8 // bias FX bit
	display  uint8 // Display ON/OFF byte
	entry    uint8 // Entry Mode byte
	addr     uint8 // cached DDRAM address
}

func newST7036Blank(conn connection.DisplayConn, lines, columns int) *ST7036Minimal {
	return &ST7036Minimal{conn: conn, lines: lines, columns: columns, n: 1, bon: 1, contrast: 0x18,
		fon: 1, rab: 5, display: 0x0C, entry: 0x06}
}

// NewST7036Minimal runs the DOGM162 3.3 V initialisation sequence: hardware
// reset, 40 ms, Function Set 0x39, Bias 0x14, Contrast 0x78, Power 0x55
// (booster on), Follower 0x6D, 200 ms, Function Set 0x38, Display on (0x0C),
// Clear, Entry Mode 0x06. The display is cleared and the cursor is at the top left.
func NewST7036Minimal(conn connection.DisplayConn) (*ST7036Minimal, error) {
	d := newST7036Blank(conn, ST7036Rows, ST7036Columns)
	if err := d.initSequence([5]uint8{0x39, 0x14, 0x55, 0x6D, 0x78}); err != nil {
		return nil, err
	}
	return d, nil
}

// Columns returns the characters per line.
func (d *ST7036Minimal) Columns() int { return d.columns }

// Rows returns the number of lines.
func (d *ST7036Minimal) Rows() int { return d.lines }

// instr sends one instruction byte (RS low) and waits waitUs microseconds.
func (d *ST7036Minimal) instr(b uint8, waitUs int) error {
	if err := d.conn.SendCommand(b, nil); err != nil {
		return err
	}
	if waitUs >= 1000 {
		d.conn.Delay(time.Duration(waitUs) * time.Microsecond)
	} else {
		d.conn.DelayMicros(waitUs)
	}
	return nil
}

func (d *ST7036Minimal) instr30(b uint8) error { return d.instr(b, 30) }

// data sends one data byte (RS high) in its own CS window, then waits 30 us.
func (d *ST7036Minimal) data(b uint8) error {
	if err := d.conn.SendData([]byte{b}); err != nil {
		return err
	}
	d.conn.DelayMicros(30)
	return nil
}

func (d *ST7036Minimal) rowStart(row int) uint8 {
	if d.lines == 3 {
		return uint8(row * 0x10)
	}
	return uint8(row * 0x40)
}

func (d *ST7036Minimal) setDDRAM(addr uint8) error {
	d.addr = addr & 0x7F
	return d.instr30(st7036CmdDDRAM | d.addr)
}

// function returns the Function Set byte for the cached N / DH and instruction table `table` (0..2).
func (d *ST7036Minimal) function(table uint8) uint8 {
	return st7036CmdFunction | 0x10 | d.n<<3 | d.dh<<2 | table&3
}

// powerByte returns the Power/ICON/Contrast high byte from the cached Ion, Bon and contrast.
func (d *ST7036Minimal) powerByte() uint8 {
	return st7036CmdPower | d.ion<<3 | d.bon<<2 | (d.contrast>>4)&3
}

// initSequence runs hardware reset, the power-up wait and the instruction
// sequence of a preset {function, bias, power, follower, contrast low}.
func (d *ST7036Minimal) initSequence(seq [5]uint8) error {
	fn, bias, power, follower, contrast := seq[0], seq[1], seq[2], seq[3], seq[4]
	if err := d.conn.Reset(); err != nil {
		return err
	}
	d.conn.Delay(40 * time.Millisecond)
	for _, b := range []uint8{fn, bias, contrast, power, follower} {
		if err := d.instr30(b); err != nil {
			return err
		}
	}
	d.conn.Delay(200 * time.Millisecond)          // booster / V0 settle
	if err := d.instr30(fn &^ 0x01); err != nil { // back to instruction table 0
		return err
	}
	if err := d.instr30(d.display); err != nil {
		return err
	}
	if err := d.instr(st7036CmdClear, 2000); err != nil {
		return err
	}
	d.addr = 0
	return d.instr30(d.entry)
}

// Clear clears the display (Clear Display, 2 ms); the cursor goes to the top left.
func (d *ST7036Minimal) Clear() error {
	d.addr = 0
	return d.instr(st7036CmdClear, 2000)
}

// Home returns the cursor (and a shifted display) to the home position (Return Home, 2 ms).
func (d *ST7036Minimal) Home() error {
	d.addr = 0
	return d.instr(st7036CmdHome, 2000)
}

// SetCursor moves the cursor to (col, row). Out-of-range positions are ignored.
func (d *ST7036Minimal) SetCursor(col, row int) error {
	if col < 0 || row < 0 || col >= d.columns || row >= d.lines {
		return nil
	}
	return d.setDDRAM(d.rowStart(row) + uint8(col))
}

// Write writes text at the cursor, one data byte per transfer with a 30 us
// gap. ASCII 0x20 .. 0x7D is written as is; any other character becomes '?'.
// The cursor does not wrap to the next line.
func (d *ST7036Minimal) Write(text string) error {
	for _, r := range text {
		c := byte('?')
		if r >= 0x20 && r <= 0x7D {
			c = byte(r)
		}
		if err := d.WriteChar(c); err != nil {
			return err
		}
	}
	return nil
}

// WriteChar writes one raw character code (including the CGRAM codes 0..7) at the cursor.
func (d *ST7036Minimal) WriteChar(code byte) error {
	if err := d.data(code); err != nil {
		return err
	}
	d.addr = (d.addr + 1) & 0x7F
	return nil
}

// PrintAt moves the cursor to (col, row) and writes text.
func (d *ST7036Minimal) PrintAt(col, row int, text string) error {
	if err := d.SetCursor(col, row); err != nil {
		return err
	}
	return d.Write(text)
}

// ST7036Full extends ST7036Minimal with the DOGM081 / DOGM162 / DOGM163
// modules at 3.3 V or 5 V, display control (on/off, cursor, blink, entry mode,
// shift), contrast, booster, follower and bias, custom characters (CGRAM),
// double height, ICON RAM and raw instructions.
type ST7036Full struct {
	*ST7036Minimal
}

// NewST7036Full runs the initialisation sequence of the DOG module.
//
// lines is 1 (DOGM081, 1 x 8), 2 (DOGM162, 2 x 16) or 3 (DOGM163, 3 x 16);
// columns is the characters per line (0 = the module default, 8 or 16);
// supply selects the booster preset; contrast is C5..C0 0..63, or -1 for the
// data sheet preset of the module. It returns ErrST7036Lines for any other
// line count.
func NewST7036Full(conn connection.DisplayConn, lines, columns int, supply ST7036Supply, contrast int) (*ST7036Full, error) {
	if lines < 1 || lines > 3 {
		return nil, ErrST7036Lines
	}
	p := st7036Presets[lines-1][supply]
	fn, bias, power, follower, low := p[0], p[1], p[2], p[3], p[4]
	if columns == 0 {
		columns = 16
		if lines == 1 {
			columns = 8
		}
	}
	d := newST7036Blank(conn, lines, columns)
	d.n = (fn >> 3) & 1
	d.bon = (power >> 2) & 1
	d.contrast = (power&3)<<4 | low&0x0F
	if contrast >= 0 {
		d.contrast = uint8(contrast) & 0x3F
		power = power&^3 | (d.contrast>>4)&3
		low = st7036CmdContrast | d.contrast&0x0F
	}
	d.rab = follower & 7
	d.quarter = (bias>>3)&1 == 1
	d.fx = bias & 1
	if err := d.initSequence([5]uint8{fn, bias, power, follower, low}); err != nil {
		return nil, err
	}
	return &ST7036Full{ST7036Minimal: d}, nil
}

func (d *ST7036Full) setDisplayBit(mask uint8, on bool) error {
	if on {
		d.display |= mask
	} else {
		d.display &^= mask
	}
	d.display = d.display&0x07 | st7036CmdDisplay
	return d.instr30(d.display)
}

// Display turns the display on or off (the DDRAM content is kept).
func (d *ST7036Full) Display(on bool) error { return d.setDisplayBit(0x04, on) }

// Cursor shows or hides the cursor (on) and its blinking block (blink).
func (d *ST7036Full) Cursor(on, blink bool) error {
	if err := d.setDisplayBit(0x02, on); err != nil {
		return err
	}
	return d.setDisplayBit(0x01, blink)
}

// SetEntryMode sets the cursor direction (increment = cursor moves right,
// address + 1) and whether the display shifts on every write.
func (d *ST7036Full) SetEntryMode(increment, shift bool) error {
	d.entry = st7036CmdEntry
	if increment {
		d.entry |= 2
	}
	if shift {
		d.entry |= 1
	}
	return d.instr30(d.entry)
}

// ShiftCursor shifts the cursor one position.
func (d *ST7036Full) ShiftCursor(right bool) error {
	b := st7036CmdShift
	if right {
		b |= 0x04
	}
	return d.instr30(b)
}

// ShiftDisplay shifts the whole display one position (the cursor follows).
func (d *ST7036Full) ShiftDisplay(right bool) error {
	b := st7036CmdShift | 0x08
	if right {
		b |= 0x04
	}
	return d.instr30(b)
}

// ReturnHome returns the cursor and a shifted display to the home position (2 ms).
func (d *ST7036Full) ReturnHome() error { return d.Home() }

// SetDDRAMAddress sets a raw DDRAM address (for use with display shift).
func (d *ST7036Full) SetDDRAMAddress(address uint8) error { return d.setDDRAM(address) }

func (d *ST7036Full) extBegin() error { return d.instr30(d.function(1)) }
func (d *ST7036Full) extEnd() error   { return d.instr30(d.function(0)) }

// ext runs the instructions between the switch to instruction table 1 and the switch back.
func (d *ST7036Full) ext(bytes ...uint8) error {
	if err := d.extBegin(); err != nil {
		return err
	}
	for _, b := range bytes {
		if err := d.instr30(b); err != nil {
			return err
		}
	}
	return d.extEnd()
}

// SetContrast sets the contrast C5..C0 (0..63).
func (d *ST7036Full) SetContrast(value uint8) error {
	d.contrast = value & 0x3F
	return d.ext(d.powerByte(), st7036CmdContrast|d.contrast&0x0F)
}

// SetBooster switches the voltage booster on or off (needed at 3.3 V).
func (d *ST7036Full) SetBooster(on bool) error {
	d.bon = b2u(on)
	return d.ext(d.powerByte())
}

// SetFollower sets the voltage follower and the V0 amplification ratio
// (Rab2..Rab0 0..7: 1 + Rb/Ra = 1, 1.25, 1.5, 1.8, 2, 2.5, 3, 3.75).
func (d *ST7036Full) SetFollower(on bool, ratio uint8) error {
	d.fon = b2u(on)
	d.rab = ratio & 7
	return d.ext(st7036CmdFollower | d.fon<<3 | d.rab)
}

// SetBias sets the LCD bias: 1/5 (default) or 1/4 (quarter); FX follows the line count.
func (d *ST7036Full) SetBias(quarter bool) error {
	d.quarter = quarter
	b := st7036CmdBias | d.fx
	if quarter {
		b |= 8
	}
	return d.ext(b)
}

// CreateChar defines a user character (CGRAM) index 0..7 from 8 row patterns
// of 5 bits (bit 4 = leftmost pixel) and restores the DDRAM address.
func (d *ST7036Full) CreateChar(index uint8, rows [8]uint8) error {
	saved := d.addr
	if err := d.instr30(st7036CmdCGRAM | (index&7)<<3); err != nil {
		return err
	}
	for _, r := range rows {
		if err := d.data(r & 0x1F); err != nil {
			return err
		}
	}
	return d.setDDRAM(saved)
}

// SetDoubleHeight selects the double-height font (1-line module: 5 x 16
// font; 3-line module: top (upper) or bottom pair of lines). It is ignored on
// the 2-line module (the combination is forbidden by the data sheet).
func (d *ST7036Full) SetDoubleHeight(on, upper bool) error {
	if d.lines == 2 {
		return nil
	}
	d.dh = b2u(on)
	table := uint8(2)
	if d.lines == 1 {
		table = 0
	}
	if err := d.instr30(d.function(table)); err != nil {
		return err
	}
	if d.lines == 3 {
		b := st7036CmdDHPos
		if upper {
			b |= 8
		}
		if err := d.instr30(b); err != nil {
			return err
		}
	}
	return d.instr30(d.function(0))
}

// Icon turns the ICON display on or off (no icons are wired on the DOG modules).
func (d *ST7036Full) Icon(on bool) error {
	d.ion = b2u(on)
	return d.ext(d.powerByte())
}

// WriteIcon writes one ICON RAM byte (address 0..15).
func (d *ST7036Full) WriteIcon(address, value uint8) error {
	saved := d.addr
	if err := d.extBegin(); err != nil {
		return err
	}
	if err := d.instr30(st7036CmdIconAddr | address&0x0F); err != nil {
		return err
	}
	if err := d.data(value); err != nil {
		return err
	}
	if err := d.extEnd(); err != nil {
		return err
	}
	return d.setDDRAM(saved)
}

// SendCommand sends any instruction byte (RS low) and waits 30 us.
func (d *ST7036Full) SendCommand(instruction uint8) error { return d.instr30(instruction) }

// SendData sends any data byte (RS high) and waits 30 us.
func (d *ST7036Full) SendData(value uint8) error { return d.WriteChar(value) }

func b2u(b bool) uint8 {
	if b {
		return 1
	}
	return 0
}
