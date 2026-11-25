package display

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SH1106 geometry and transfer limits.
const (
	// SH1106Width and SH1106Height are the maximum dimensions of the SH1106 RAM (132 columns x 64 rows).
	SH1106Width  = 132
	SH1106Height = 64
	// SH1106I2CChunk is the largest I2C write in bytes including the control byte (the 32-byte limit of some I2C layers).
	SH1106I2CChunk = 32
	// SH1106SPIChunk is the largest data write in bytes on the SPI transport (one page).
	SH1106SPIChunk = 132
)

// ErrSH1106Argument is returned for a size, position or buffer argument that is out of range.
var ErrSH1106Argument = errors.New("sh1106: argument out of range")

// ErrSH1106Unsupported is returned by ReadStatus and ReadPage on the 4-wire SPI interface, which is write-only.
var ErrSH1106Unsupported = errors.New("sh1106: the SPI interface is write-only")

// sh1106Bus is the transport of the driver.
type sh1106Bus interface {
	writeCommands(b []byte) error
	writeData(b []byte) error
	canRead() bool
	readStatus() (byte, error)
	readRAM(n int) ([]byte, error)
	hardwareReset() error
}

// sh1106I2C is the I2C transport: every transaction starts with a control byte (0x00 commands, 0x40 RAM data).
type sh1106I2C struct{ conn connection.Connection }

func (t *sh1106I2C) writeCommands(b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > SH1106I2CChunk-1 {
			n = SH1106I2CChunk - 1
		}
		if err := t.conn.Write(append([]byte{0x00}, b[:n]...)); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (t *sh1106I2C) writeData(b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > SH1106I2CChunk-1 {
			n = SH1106I2CChunk - 1
		}
		if err := t.conn.Write(append([]byte{0x40}, b[:n]...)); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (t *sh1106I2C) canRead() bool { return true }

func (t *sh1106I2C) readStatus() (byte, error) {
	if err := t.conn.Write([]byte{0x00}); err != nil {
		return 0, err
	}
	b, err := t.conn.Read(1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (t *sh1106I2C) readRAM(n int) ([]byte, error) {
	if err := t.conn.Write([]byte{0x40}); err != nil {
		return nil, err
	}
	b, err := t.conn.Read(n + 1)
	if err != nil {
		return nil, err
	}
	return append([]byte(nil), b[1:]...), nil
}

func (t *sh1106I2C) hardwareReset() error { return nil }

// sh1106SPI is the 4-wire SPI transport: D/C low for every command byte, high for RAM data.
type sh1106SPI struct{ conn connection.DisplayConn }

func (t *sh1106SPI) writeCommands(b []byte) error {
	for _, c := range b {
		if err := t.conn.SendCommand(c, nil); err != nil {
			return err
		}
	}
	return nil
}

func (t *sh1106SPI) writeData(b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > SH1106SPIChunk {
			n = SH1106SPIChunk
		}
		if err := t.conn.SendData(b[:n]); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (t *sh1106SPI) canRead() bool                 { return false }
func (t *sh1106SPI) readStatus() (byte, error)     { return 0, ErrSH1106Unsupported }
func (t *sh1106SPI) readRAM(n int) ([]byte, error) { return nil, ErrSH1106Unsupported }
func (t *sh1106SPI) hardwareReset() error          { return t.conn.Reset() }

const (
	sh1106CmdColLo     = 0x00
	sh1106CmdColHi     = 0x10
	sh1106CmdPump      = 0x30
	sh1106CmdStartLine = 0x40
	sh1106CmdContrast  = 0x81
	sh1106CmdSegRemap  = 0xA0
	sh1106CmdAllOn     = 0xA4
	sh1106CmdInvert    = 0xA6
	sh1106CmdMultiplex = 0xA8
	sh1106CmdDCDC      = 0xAD
	sh1106CmdDisplay   = 0xAE
	sh1106CmdPage      = 0xB0
	sh1106CmdScan      = 0xC0
	sh1106CmdOffset    = 0xD3
	sh1106CmdClock     = 0xD5
	sh1106CmdPrecharge = 0xD9
	sh1106CmdCom       = 0xDA
	sh1106CmdVcom      = 0xDB
)

// SH1106Minimal is the SH1106 132 x 64 OLED controller-driver — minimal interface.
//
// Drives a monochrome SH1106 OLED panel (typically a 1.3" 128 x 64 module) over I2C at 0x3C (SA0 = VSS)
// or 0x3D (SA0 = VDD1). The chip has 132 RAM columns; 128-pixel-wide modules show RAM columns 2..129, so
// pass columnOffset 2 for them (0 for a true 132-column panel).
//
// All drawing goes into a framebuffer of width * height / 8 bytes (page-major, bit 0 = top pixel of the
// page); Show sends it page by page (the chip has page addressing only). The initialisation follows the
// datasheet power-on flow with the internal DC-DC: setup stream with the display off, DC-DC on + 100 ms,
// RAM cleared, display on + 150 ms.
type SH1106Minimal struct {
	bus    sh1106Bus
	width  int
	height int
	pages  int
	offset int
	dcdc   bool
	adc    byte
	scan   byte
	fb     []byte
}

// NewSH1106Minimal creates the driver on an I2C connection and runs the initialisation sequence (about 250 ms).
//
// conn is the connection at 0x3C or 0x3D. width is 1..132 and height a multiple of 8, 8..64 (pass 0 for the
// defaults 128 and 64). columnOffset is the first RAM column shown (2 for 128-pixel modules). internalDCDC
// selects the internal charge pump (false: external VPP). It returns ErrSH1106Argument for bad arguments.
func NewSH1106Minimal(conn connection.Connection, width, height, columnOffset int, internalDCDC bool) (*SH1106Minimal, error) {
	return newSH1106(&sh1106I2C{conn}, width, height, columnOffset, internalDCDC)
}

// NewSH1106SpiMinimal creates the driver on a 4-wire SPI display connection (CS, D/C = A0, RES optional, no
// BUSY; SPI mode 0, up to 2 MHz; chip strapped IM0 = IM1 = IM2 = 0). Every command byte, including
// arguments, is sent with D/C low; RAM data with D/C high. SPI cannot read: ReadStatus / ReadPage of the
// full driver return ErrSH1106Unsupported. Arguments as for NewSH1106Minimal.
func NewSH1106SpiMinimal(conn connection.DisplayConn, width, height, columnOffset int, internalDCDC bool) (*SH1106Minimal, error) {
	return newSH1106(&sh1106SPI{conn}, width, height, columnOffset, internalDCDC)
}

func newSH1106(bus sh1106Bus, width, height, columnOffset int, internalDCDC bool) (*SH1106Minimal, error) {
	if width == 0 {
		width = 128
	}
	if height == 0 {
		height = SH1106Height
	}
	if width < 1 || width > SH1106Width || height < 8 || height > SH1106Height || height%8 != 0 ||
		columnOffset < 0 || columnOffset+width > SH1106Width {
		return nil, ErrSH1106Argument
	}
	d := &SH1106Minimal{
		bus: bus, width: width, height: height, pages: height / 8, offset: columnOffset,
		dcdc: internalDCDC, fb: make([]byte, width*(height/8)),
	}
	if err := d.initSequence(); err != nil {
		return nil, err
	}
	return d, nil
}

// Width returns the visible columns of the framebuffer.
func (d *SH1106Minimal) Width() int { return d.width }

// Height returns the rows of the framebuffer.
func (d *SH1106Minimal) Height() int { return d.height }

// Clear zeroes the framebuffer. Call Show to update the panel.
func (d *SH1106Minimal) Clear() {
	for i := range d.fb {
		d.fb[i] = 0
	}
}

// SetPixel sets or clears one pixel in the framebuffer. Out-of-range coordinates are ignored.
func (d *SH1106Minimal) SetPixel(x, y int, on bool) {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return
	}
	i := (y>>3)*d.width + x
	if on {
		d.fb[i] |= 1 << uint(y&7)
	} else {
		d.fb[i] &^= 1 << uint(y&7)
	}
}

// Show writes the whole framebuffer to the display RAM (page + column command, then width data bytes per page).
func (d *SH1106Minimal) Show() error {
	for page := 0; page < d.pages; page++ {
		if err := d.showPage(page); err != nil {
			return err
		}
	}
	return nil
}

// ---- internals ------------------------------------------------------------------------------

func (d *SH1106Minimal) command(b ...byte) error { return d.bus.writeCommands(b) }

func sh1106Address(page, column int) []byte {
	return []byte{byte(sh1106CmdPage | page&7), byte(sh1106CmdColLo | column&0x0F), byte(sh1106CmdColHi | (column>>4)&0x0F)}
}

func (d *SH1106Minimal) showPage(page int) error {
	if err := d.bus.writeCommands(sh1106Address(page, d.offset)); err != nil {
		return err
	}
	start := page * d.width
	return d.bus.writeData(d.fb[start : start+d.width])
}

// initSequence is the datasheet power-on flow: setup stream, DC-DC, RAM clear, display on.
func (d *SH1106Minimal) initSequence() error {
	if err := d.bus.hardwareReset(); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	if err := d.command(sh1106CmdDisplay,
		sh1106CmdClock, 0x50,
		sh1106CmdMultiplex, byte(d.height-1),
		sh1106CmdOffset, 0x00,
		sh1106CmdStartLine,
		sh1106CmdSegRemap|d.adc,
		sh1106CmdScan|d.scan<<3,
		sh1106CmdCom, 0x12,
		sh1106CmdContrast, 0x80,
		sh1106CmdPrecharge, 0x22,
		sh1106CmdVcom, 0x35,
		sh1106CmdPump,
		sh1106CmdAllOn,
		sh1106CmdInvert); err != nil {
		return err
	}
	if d.dcdc {
		if err := d.command(sh1106CmdDCDC, 0x8B); err != nil {
			return err
		}
		time.Sleep(100 * time.Millisecond)
	} else if err := d.command(sh1106CmdDCDC, 0x8A); err != nil {
		return err
	}
	blank := make([]byte, SH1106Width)
	for page := 0; page < 8; page++ {
		if err := d.bus.writeCommands(sh1106Address(page, 0)); err != nil {
			return err
		}
		if err := d.bus.writeData(blank); err != nil {
			return err
		}
	}
	if err := d.command(sh1106CmdDisplay | 1); err != nil {
		return err
	}
	if d.dcdc {
		time.Sleep(150 * time.Millisecond)
	} else {
		time.Sleep(50 * time.Millisecond)
	}
	return nil
}

// SH1106Full is the SH1106 OLED controller-driver — full interface.
//
// It extends SH1106Minimal with drawing primitives, contrast, sleep, invert, all-on test, flip, hardware
// scroll (display start line), display offset, multiplex ratio, clock / oscillator, pre-charge, COM
// configuration, VCOM level, pump voltage, DC-DC control, status and RAM reads (I2C only) and raw commands.
type SH1106Full struct {
	*SH1106Minimal
}

// NewSH1106Full creates the driver on an I2C connection and runs the initialisation sequence (arguments as
// for NewSH1106Minimal).
func NewSH1106Full(conn connection.Connection, width, height, columnOffset int, internalDCDC bool) (*SH1106Full, error) {
	m, err := newSH1106(&sh1106I2C{conn}, width, height, columnOffset, internalDCDC)
	if err != nil {
		return nil, err
	}
	return &SH1106Full{m}, nil
}

// NewSH1106SpiFull creates the full driver on a 4-wire SPI display connection (see NewSH1106SpiMinimal).
func NewSH1106SpiFull(conn connection.DisplayConn, width, height, columnOffset int, internalDCDC bool) (*SH1106Full, error) {
	m, err := newSH1106(&sh1106SPI{conn}, width, height, columnOffset, internalDCDC)
	if err != nil {
		return nil, err
	}
	return &SH1106Full{m}, nil
}

// GetPixel returns a framebuffer pixel; out-of-range coordinates read as false.
func (d *SH1106Full) GetPixel(x, y int) bool {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return false
	}
	return d.fb[(y>>3)*d.width+x]&(1<<uint(y&7)) != 0
}

// Fill sets (or clears) the whole framebuffer.
func (d *SH1106Full) Fill(on bool) {
	var v byte
	if on {
		v = 0xFF
	}
	for i := range d.fb {
		d.fb[i] = v
	}
}

func sh1106Abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// DrawLine draws a Bresenham line into the framebuffer.
func (d *SH1106Full) DrawLine(x0, y0, x1, y1 int, on bool) {
	dx := sh1106Abs(x1 - x0)
	dy := -sh1106Abs(y1 - y0)
	sx, sy := -1, -1
	if x0 < x1 {
		sx = 1
	}
	if y0 < y1 {
		sy = 1
	}
	err := dx + dy
	for {
		d.SetPixel(x0, y0, on)
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
}

// DrawRect draws a rectangle outline or a filled rectangle into the framebuffer.
func (d *SH1106Full) DrawRect(x, y, w, h int, on, fill bool) {
	if w <= 0 || h <= 0 {
		return
	}
	if fill {
		for yy := y; yy < y+h; yy++ {
			for xx := x; xx < x+w; xx++ {
				d.SetPixel(xx, yy, on)
			}
		}
		return
	}
	d.DrawLine(x, y, x+w-1, y, on)
	d.DrawLine(x, y+h-1, x+w-1, y+h-1, on)
	d.DrawLine(x, y, x, y+h-1, on)
	d.DrawLine(x+w-1, y, x+w-1, y+h-1, on)
}

// Blit copies a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer. columns holds
// heightPages groups of columns, page by page: byte p*n+c is column c of page row p (n = len(columns) /
// heightPages). The bitmap is clipped to the framebuffer; y must be a multiple of 8, else ErrSH1106Argument.
func (d *SH1106Full) Blit(x, y int, columns []byte, heightPages int) error {
	if y%8 != 0 || heightPages <= 0 {
		return ErrSH1106Argument
	}
	n := len(columns) / heightPages
	for p := 0; p < heightPages; p++ {
		page := y/8 + p
		if page < 0 || page >= d.pages {
			continue
		}
		for c := 0; c < n; c++ {
			xx := x + c
			if xx >= 0 && xx < d.width {
				d.fb[page*d.width+xx] = columns[p*n+c]
			}
		}
	}
	return nil
}

// ShowPage writes one framebuffer page to the display RAM (cheaper partial update); out-of-range pages are ignored.
func (d *SH1106Full) ShowPage(page int) error {
	if page < 0 || page >= d.pages {
		return nil
	}
	return d.showPage(page)
}

// SetContrast sets the segment output current (contrast), 0..255 (reset 0x80).
func (d *SH1106Full) SetContrast(value uint8) error { return d.command(sh1106CmdContrast, value) }

func sh1106Bit(b bool) byte {
	if b {
		return 1
	}
	return 0
}

// SetDisplay switches the display on or off (RAM and settings are kept).
func (d *SH1106Full) SetDisplay(on bool) error { return d.command(sh1106CmdDisplay | sh1106Bit(on)) }

// Sleep turns the display off; the DC-DC stops, RAM and settings are kept and stay writable.
func (d *SH1106Full) Sleep() error { return d.SetDisplay(false) }

// Wake turns the display on and waits 100 ms for VPP when the internal DC-DC is used.
func (d *SH1106Full) Wake() error {
	if err := d.SetDisplay(true); err != nil {
		return err
	}
	if d.dcdc {
		time.Sleep(100 * time.Millisecond)
	} else {
		time.Sleep(50 * time.Millisecond)
	}
	return nil
}

// SetInvert inverts the displayed image without changing RAM.
func (d *SH1106Full) SetInvert(on bool) error { return d.command(sh1106CmdInvert | sh1106Bit(on)) }

// SetAllOn forces all pixels on (test mode, RAM unchanged).
func (d *SH1106Full) SetAllOn(on bool) error { return d.command(sh1106CmdAllOn | sh1106Bit(on)) }

// SetFlip mirrors the columns (segment re-map, ADC) and/or rows (COM scan direction); the framebuffer is unchanged.
func (d *SH1106Full) SetFlip(horizontal, vertical bool) error {
	d.adc = sh1106Bit(horizontal)
	d.scan = sh1106Bit(vertical)
	return d.command(sh1106CmdSegRemap|d.adc, sh1106CmdScan|d.scan<<3)
}

// SetStartLine sets the RAM line shown at COM0 (hardware vertical scroll), 0..63.
func (d *SH1106Full) SetStartLine(line uint8) error { return d.command(sh1106CmdStartLine | line&0x3F) }

// SetDisplayOffset shifts the COM mapping, 0..63.
func (d *SH1106Full) SetDisplayOffset(offset uint8) error {
	return d.command(sh1106CmdOffset, offset&0x3F)
}

// SetMultiplex sets the multiplex ratio, 1..64.
func (d *SH1106Full) SetMultiplex(ratio uint8) error {
	return d.command(sh1106CmdMultiplex, (ratio-1)&0x3F)
}

// SetClock sets the clock divide ratio (1..16) and the oscillator setting (0..15, 5 = nominal).
func (d *SH1106Full) SetClock(divide, osc uint8) error {
	return d.command(sh1106CmdClock, (osc&0x0F)<<4|(divide-1)&0x0F)
}

// SetPrecharge sets the pre-charge (1..15) and discharge (1..15) periods in display clocks.
func (d *SH1106Full) SetPrecharge(precharge, discharge uint8) error {
	return d.command(sh1106CmdPrecharge, (discharge&0x0F)<<4|precharge&0x0F)
}

// SetComConfig selects the COM pin configuration: alternative (0x12, reset) or sequential (0x02).
func (d *SH1106Full) SetComConfig(alternative bool) error {
	if alternative {
		return d.command(sh1106CmdCom, 0x12)
	}
	return d.command(sh1106CmdCom, 0x02)
}

// SetVcomDeselect sets the VCOM deselect level, 0..255 (reset 0x35).
func (d *SH1106Full) SetVcomDeselect(value uint8) error { return d.command(sh1106CmdVcom, value) }

// SetPumpVoltage sets the charge pump voltage: 0 = 7.4 V (reset), 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V.
func (d *SH1106Full) SetPumpVoltage(level uint8) error { return d.command(sh1106CmdPump | level&3) }

// SetDCDC switches the internal DC-DC on (0x8B) or off (0x8A, external VPP); call with the display off.
func (d *SH1106Full) SetDCDC(on bool) error {
	d.dcdc = on
	if on {
		return d.command(sh1106CmdDCDC, 0x8B)
	}
	return d.command(sh1106CmdDCDC, 0x8A)
}

// ReadStatus reads the status byte (I2C only): bit 7 BUSY, bit 6 = 1 when the display is off.
// It returns ErrSH1106Unsupported on SPI.
func (d *SH1106Full) ReadStatus() (byte, error) {
	if !d.bus.canRead() {
		return 0, ErrSH1106Unsupported
	}
	return d.bus.readStatus()
}

// ReadPage reads n bytes (1..30) of display RAM from the given page and RAM column (I2C only); the dummy
// byte after the address change is discarded. It returns ErrSH1106Unsupported on SPI.
func (d *SH1106Full) ReadPage(page, column, n int) ([]byte, error) {
	if !d.bus.canRead() {
		return nil, ErrSH1106Unsupported
	}
	if n < 1 || n > SH1106I2CChunk-2 || page < 0 || page > 7 {
		return nil, ErrSH1106Argument
	}
	if err := d.bus.writeCommands(sh1106Address(page, column)); err != nil {
		return nil, err
	}
	return d.bus.readRAM(n)
}

// SendCommand sends raw bytes as one command stream.
func (d *SH1106Full) SendCommand(b ...byte) error { return d.command(b...) }
