package display

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SSD1305 geometry and transfer limits.
const (
	// SSD1305Width and SSD1305Height are the maximum dimensions of the SSD1305 RAM (132 columns x 64 rows).
	SSD1305Width  = 132
	SSD1305Height = 64
	// SSD1305I2CChunk is the largest I2C write in bytes including the control byte (the 32-byte limit of some I2C layers).
	SSD1305I2CChunk = 32
	// SSD1305SPIChunk is the largest data write in bytes on the SPI transport (one page).
	SSD1305SPIChunk = 132
)

// ErrSSD1305Argument is returned for a size, position or buffer argument that is out of range.
var ErrSSD1305Argument = errors.New("ssd1305: argument out of range")

// ErrSSD1305Unsupported is returned by ReadStatus and ReadPage on the 4-wire SPI interface, which is write-only.
var ErrSSD1305Unsupported = errors.New("ssd1305: the SPI interface is write-only")

// ssd1305Bus is the transport of the driver.
type ssd1305Bus interface {
	writeCommands(b []byte) error
	writeData(b []byte) error
	canRead() bool
	readStatus() (byte, error)
	readRAM(n int) ([]byte, error)
	hardwareReset() error
}

// ssd1305I2C is the I2C transport: every transaction starts with a control byte (0x00 commands, 0x40 RAM data).
type ssd1305I2C struct{ conn connection.Connection }

func (t *ssd1305I2C) writeCommands(b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > SSD1305I2CChunk-1 {
			n = SSD1305I2CChunk - 1
		}
		if err := t.conn.Write(append([]byte{0x00}, b[:n]...)); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (t *ssd1305I2C) writeData(b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > SSD1305I2CChunk-1 {
			n = SSD1305I2CChunk - 1
		}
		if err := t.conn.Write(append([]byte{0x40}, b[:n]...)); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (t *ssd1305I2C) canRead() bool { return true }

func (t *ssd1305I2C) readStatus() (byte, error) {
	if err := t.conn.Write([]byte{0x00}); err != nil {
		return 0, err
	}
	b, err := t.conn.Read(1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (t *ssd1305I2C) readRAM(n int) ([]byte, error) {
	if err := t.conn.Write([]byte{0x40}); err != nil {
		return nil, err
	}
	b, err := t.conn.Read(n + 1)
	if err != nil {
		return nil, err
	}
	return append([]byte(nil), b[1:]...), nil
}

func (t *ssd1305I2C) hardwareReset() error { return nil }

// ssd1305SPI is the 4-wire SPI transport: D/C low for every command byte, high for RAM data.
type ssd1305SPI struct{ conn connection.DisplayConn }

func (t *ssd1305SPI) writeCommands(b []byte) error {
	for _, c := range b {
		if err := t.conn.SendCommand(c, nil); err != nil {
			return err
		}
	}
	return nil
}

func (t *ssd1305SPI) writeData(b []byte) error {
	for len(b) > 0 {
		n := len(b)
		if n > SSD1305SPIChunk {
			n = SSD1305SPIChunk
		}
		if err := t.conn.SendData(b[:n]); err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func (t *ssd1305SPI) canRead() bool                 { return false }
func (t *ssd1305SPI) readStatus() (byte, error)     { return 0, ErrSSD1305Unsupported }
func (t *ssd1305SPI) readRAM(n int) ([]byte, error) { return nil, ErrSSD1305Unsupported }
func (t *ssd1305SPI) hardwareReset() error          { return t.conn.Reset() }

const (
	ssd1305CmdColLo      = 0x00
	ssd1305CmdColHi      = 0x10
	ssd1305CmdAddrMode   = 0x20
	ssd1305CmdColRange   = 0x21
	ssd1305CmdPageRange  = 0x22
	ssd1305CmdStartLine  = 0x40
	ssd1305CmdContrast   = 0x81
	ssd1305CmdBrightness = 0x82
	ssd1305CmdLUT        = 0x91
	ssd1305CmdBank1to16  = 0x92
	ssd1305CmdBank17to32 = 0x93
	ssd1305CmdSegRemap   = 0xA0
	ssd1305CmdScrollArea = 0xA3
	ssd1305CmdAllOn      = 0xA4
	ssd1305CmdInvert     = 0xA6
	ssd1305CmdMultiplex  = 0xA8
	ssd1305CmdDimMode    = 0xAB
	ssd1305CmdDimOn      = 0xAC
	ssd1305CmdMaster     = 0xAD
	ssd1305CmdDisplay    = 0xAE
	ssd1305CmdPage       = 0xB0
	ssd1305CmdScan       = 0xC0
	ssd1305CmdOffset     = 0xD3
	ssd1305CmdClock      = 0xD5
	ssd1305CmdAreaColor  = 0xD8
	ssd1305CmdPrecharge  = 0xD9
	ssd1305CmdCom        = 0xDA
	ssd1305CmdVcomh      = 0xDB
)

// SSD1305AddressingMode selects how Show writes the display RAM (see SetAddressingMode).
type SSD1305AddressingMode byte

// RAM addressing modes.
const (
	// SSD1305AddrHorizontal advances the column pointer first, then the page (0x20 0x00).
	SSD1305AddrHorizontal SSD1305AddressingMode = 0
	// SSD1305AddrVertical advances the page pointer first, then the column (0x20 0x01).
	SSD1305AddrVertical SSD1305AddressingMode = 1
	// SSD1305AddrPage writes one page at a time with page + column commands (0x20 0x02, reset default).
	SSD1305AddrPage SSD1305AddressingMode = 2
)

// SSD1305Minimal is the SSD1305 132 x 64 OLED controller-driver — minimal interface.
//
// Drives a monochrome SSD1305 OLED panel (2.23" 128 x 32 or 1.3" 128 x 64 modules) over I2C at 0x3C
// (SA0 = low) or 0x3D (SA0 = high). The chip has 132 RAM columns; 128-pixel-wide modules usually show RAM
// columns 4..131, so pass columnOffset 4 for them (0 for a true 132-column panel).
//
// All drawing goes into a framebuffer of width * height / 8 bytes (page-major, bit 0 = top pixel of the
// page); Show sends it page by page (page addressing, the reset default). The chip has no charge pump: the
// OLED supply VCC (7..15 V) must be supplied by the board. The initialisation follows the datasheet power-on
// flow: setup stream with the display off, RAM cleared, display on + 100 ms.
type SSD1305Minimal struct {
	bus    ssd1305Bus
	width  int
	height int
	pages  int
	offset int
	mode   SSD1305AddressingMode
	adc    byte
	scan   byte
	fb     []byte
}

// NewSSD1305Minimal creates the driver on an I2C connection and runs the initialisation sequence (about 100 ms).
//
// conn is the connection at 0x3C or 0x3D. width is 1..132 and height a multiple of 8, 16..64 (pass 0 for the
// defaults 128 and 64; heights up to 32 use sequential COM wiring). columnOffset is the first RAM column
// shown (4 for 128-pixel modules). It returns ErrSSD1305Argument for bad arguments.
func NewSSD1305Minimal(conn connection.Connection, width, height, columnOffset int) (*SSD1305Minimal, error) {
	return newSSD1305(&ssd1305I2C{conn}, width, height, columnOffset)
}

// NewSSD1305SpiMinimal creates the driver on a 4-wire SPI display connection (CS, D/C, RES optional, no
// BUSY; SPI mode 0, up to 4 MHz, 2 MHz recommended; chip strapped BS0 = BS1 = BS2 = 0). Every command byte, including
// arguments, is sent with D/C low; RAM data with D/C high. SPI cannot read: ReadStatus / ReadPage of the
// full driver return ErrSSD1305Unsupported. Arguments as for NewSSD1305Minimal.
func NewSSD1305SpiMinimal(conn connection.DisplayConn, width, height, columnOffset int) (*SSD1305Minimal, error) {
	return newSSD1305(&ssd1305SPI{conn}, width, height, columnOffset)
}

func newSSD1305(bus ssd1305Bus, width, height, columnOffset int) (*SSD1305Minimal, error) {
	if width == 0 {
		width = 128
	}
	if height == 0 {
		height = SSD1305Height
	}
	if width < 1 || width > SSD1305Width || height < 16 || height > SSD1305Height || height%8 != 0 ||
		columnOffset < 0 || columnOffset+width > SSD1305Width {
		return nil, ErrSSD1305Argument
	}
	d := &SSD1305Minimal{
		bus: bus, width: width, height: height, pages: height / 8, offset: columnOffset,
		mode: SSD1305AddrPage, fb: make([]byte, width*(height/8)),
	}
	if err := d.initSequence(); err != nil {
		return nil, err
	}
	return d, nil
}

// Width returns the visible columns of the framebuffer.
func (d *SSD1305Minimal) Width() int { return d.width }

// Height returns the rows of the framebuffer.
func (d *SSD1305Minimal) Height() int { return d.height }

// Clear zeroes the framebuffer. Call Show to update the panel.
func (d *SSD1305Minimal) Clear() {
	for i := range d.fb {
		d.fb[i] = 0
	}
}

// SetPixel sets or clears one pixel in the framebuffer. Out-of-range coordinates are ignored.
func (d *SSD1305Minimal) SetPixel(x, y int, on bool) {
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

// Show writes the whole framebuffer to the display RAM. Page addressing (default): per page a page + column
// command stream, then width data bytes. Horizontal / vertical addressing: column and page range commands,
// then one data stream.
func (d *SSD1305Minimal) Show() error { return d.showAll() }

func (d *SSD1305Minimal) showAll() error {
	if d.mode == SSD1305AddrPage {
		for page := 0; page < d.pages; page++ {
			if err := d.showPage(page); err != nil {
				return err
			}
		}
		return nil
	}
	if err := d.command(ssd1305CmdColRange, byte(d.offset), byte(d.offset+d.width-1),
		ssd1305CmdPageRange, 0, byte(d.pages-1)); err != nil {
		return err
	}
	if d.mode == SSD1305AddrHorizontal {
		return d.bus.writeData(d.fb)
	}
	out := make([]byte, 0, len(d.fb))
	for x := 0; x < d.width; x++ {
		for p := 0; p < d.pages; p++ {
			out = append(out, d.fb[p*d.width+x])
		}
	}
	return d.bus.writeData(out)
}

// ---- internals ------------------------------------------------------------------------------

func (d *SSD1305Minimal) command(b ...byte) error { return d.bus.writeCommands(b) }

func ssd1305Address(page, column int) []byte {
	return []byte{byte(ssd1305CmdPage | page&7), byte(ssd1305CmdColLo | column&0x0F), byte(ssd1305CmdColHi | (column>>4)&0x0F)}
}

func (d *SSD1305Minimal) showPage(page int) error {
	if err := d.bus.writeCommands(ssd1305Address(page, d.offset)); err != nil {
		return err
	}
	start := page * d.width
	return d.bus.writeData(d.fb[start : start+d.width])
}

// initSequence is the datasheet power-on flow: reset, setup stream, RAM clear, display on.
func (d *SSD1305Minimal) initSequence() error {
	if err := d.bus.hardwareReset(); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	com := byte(0x12)
	if d.height <= 32 {
		com = 0x02
	}
	if err := d.command(ssd1305CmdDisplay,
		ssd1305CmdClock, 0x70,
		ssd1305CmdMultiplex, byte(d.height-1),
		ssd1305CmdOffset, 0x00,
		ssd1305CmdStartLine,
		ssd1305CmdSegRemap|d.adc,
		ssd1305CmdScan|d.scan<<3,
		ssd1305CmdCom, com,
		ssd1305CmdContrast, 0x80,
		ssd1305CmdPrecharge, 0x22,
		ssd1305CmdVcomh, 0x34,
		ssd1305CmdMaster, 0x8E,
		ssd1305CmdAllOn,
		ssd1305CmdInvert,
		ssd1305CmdAddrMode, 0x02); err != nil {
		return err
	}
	blank := make([]byte, SSD1305Width)
	for page := 0; page < 8; page++ {
		if err := d.bus.writeCommands(ssd1305Address(page, 0)); err != nil {
			return err
		}
		if err := d.bus.writeData(blank); err != nil {
			return err
		}
	}
	if err := d.command(ssd1305CmdDisplay | 1); err != nil {
		return err
	}
	time.Sleep(100 * time.Millisecond)
	return nil
}

// SSD1305Full is the SSD1305 OLED controller-driver — full interface.
//
// It extends SSD1305Minimal with drawing primitives, contrast, brightness, dim mode, sleep, invert, all-on
// test, flip, start line, display offset, multiplex ratio, clock / oscillator, pre-charge, COM
// configuration, VCOMH level, addressing mode, hardware scrolling, area-colour mode with LUT / bank colours,
// status and RAM reads (I2C only) and raw commands.
type SSD1305Full struct {
	*SSD1305Minimal
}

// NewSSD1305Full creates the driver on an I2C connection and runs the initialisation sequence (arguments as
// for NewSSD1305Minimal).
func NewSSD1305Full(conn connection.Connection, width, height, columnOffset int) (*SSD1305Full, error) {
	m, err := newSSD1305(&ssd1305I2C{conn}, width, height, columnOffset)
	if err != nil {
		return nil, err
	}
	return &SSD1305Full{m}, nil
}

// NewSSD1305SpiFull creates the full driver on a 4-wire SPI display connection (see NewSSD1305SpiMinimal).
func NewSSD1305SpiFull(conn connection.DisplayConn, width, height, columnOffset int) (*SSD1305Full, error) {
	m, err := newSSD1305(&ssd1305SPI{conn}, width, height, columnOffset)
	if err != nil {
		return nil, err
	}
	return &SSD1305Full{m}, nil
}

// GetPixel returns a framebuffer pixel; out-of-range coordinates read as false.
func (d *SSD1305Full) GetPixel(x, y int) bool {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return false
	}
	return d.fb[(y>>3)*d.width+x]&(1<<uint(y&7)) != 0
}

// Fill sets (or clears) the whole framebuffer.
func (d *SSD1305Full) Fill(on bool) {
	var v byte
	if on {
		v = 0xFF
	}
	for i := range d.fb {
		d.fb[i] = v
	}
}

func ssd1305Abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// DrawLine draws a Bresenham line into the framebuffer.
func (d *SSD1305Full) DrawLine(x0, y0, x1, y1 int, on bool) {
	dx := ssd1305Abs(x1 - x0)
	dy := -ssd1305Abs(y1 - y0)
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
func (d *SSD1305Full) DrawRect(x, y, w, h int, on, fill bool) {
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
// heightPages). The bitmap is clipped to the framebuffer; y must be a multiple of 8, else ErrSSD1305Argument.
func (d *SSD1305Full) Blit(x, y int, columns []byte, heightPages int) error {
	if y%8 != 0 || heightPages <= 0 {
		return ErrSSD1305Argument
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
func (d *SSD1305Full) ShowPage(page int) error {
	if page < 0 || page >= d.pages {
		return nil
	}
	if d.mode == SSD1305AddrPage {
		return d.showPage(page)
	}
	if err := d.command(ssd1305CmdColRange, byte(d.offset), byte(d.offset+d.width-1),
		ssd1305CmdPageRange, byte(page), byte(page)); err != nil {
		return err
	}
	start := page * d.width
	return d.bus.writeData(d.fb[start : start+d.width])
}

// SetContrast sets the segment output current (contrast), 0..255 (reset 0x80).
func (d *SSD1305Full) SetContrast(value uint8) error { return d.command(ssd1305CmdContrast, value) }

func ssd1305Bit(b bool) byte {
	if b {
		return 1
	}
	return 0
}

// SetDisplay switches the display on or off (RAM and settings are kept).
func (d *SSD1305Full) SetDisplay(on bool) error { return d.command(ssd1305CmdDisplay | ssd1305Bit(on)) }

// Sleep turns the display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards.
func (d *SSD1305Full) Sleep() error { return d.SetDisplay(false) }

// Wake turns the display on and waits 100 ms (tAF) for SEG / COM to turn on.
func (d *SSD1305Full) Wake() error {
	if err := d.SetDisplay(true); err != nil {
		return err
	}
	time.Sleep(100 * time.Millisecond)
	return nil
}

// SetDim switches between dim mode (true, 0xAC) and normal display on (false, 0xAF); configure the levels with SetDimMode first.
func (d *SSD1305Full) SetDim(on bool) error {
	if on {
		return d.command(ssd1305CmdDimOn)
	}
	return d.command(ssd1305CmdDisplay | 1)
}

// SetDimMode configures the BANK0 contrast and colour-bank brightness used in dim mode (0xAB).
func (d *SSD1305Full) SetDimMode(contrast, brightness uint8) error {
	return d.command(ssd1305CmdDimMode, 0x00, contrast, brightness)
}

// SetInvert inverts the displayed image without changing RAM.
func (d *SSD1305Full) SetInvert(on bool) error { return d.command(ssd1305CmdInvert | ssd1305Bit(on)) }

// SetAllOn forces all pixels on (test mode, RAM unchanged).
func (d *SSD1305Full) SetAllOn(on bool) error { return d.command(ssd1305CmdAllOn | ssd1305Bit(on)) }

// SetFlip mirrors the columns (segment re-map, ADC) and/or rows (COM scan direction); the framebuffer is unchanged.
func (d *SSD1305Full) SetFlip(horizontal, vertical bool) error {
	d.adc = ssd1305Bit(horizontal)
	d.scan = ssd1305Bit(vertical)
	return d.command(ssd1305CmdSegRemap|d.adc, ssd1305CmdScan|d.scan<<3)
}

// SetStartLine sets the RAM line shown at COM0 (hardware vertical scroll), 0..63.
func (d *SSD1305Full) SetStartLine(line uint8) error {
	return d.command(ssd1305CmdStartLine | line&0x3F)
}

// SetDisplayOffset shifts the COM mapping, 0..63.
func (d *SSD1305Full) SetDisplayOffset(offset uint8) error {
	return d.command(ssd1305CmdOffset, offset&0x3F)
}

// SetMultiplex sets the multiplex ratio, 16..64.
func (d *SSD1305Full) SetMultiplex(ratio uint8) error {
	return d.command(ssd1305CmdMultiplex, (ratio-1)&0x3F)
}

// SetClock sets the clock divide ratio (1..16) and the oscillator setting (0..15, 7 = reset).
func (d *SSD1305Full) SetClock(divide, osc uint8) error {
	return d.command(ssd1305CmdClock, (osc&0x0F)<<4|(divide-1)&0x0F)
}

// SetPrecharge sets the pre-charge phase 1 and phase 2 periods (1..15 display clocks each).
func (d *SSD1305Full) SetPrecharge(phase1, phase2 uint8) error {
	return d.command(ssd1305CmdPrecharge, (phase2&0x0F)<<4|phase1&0x0F)
}

// SetComConfig selects the COM pin configuration: alternative (true, reset) or sequential; remap enables the
// COM left / right remap.
func (d *SSD1305Full) SetComConfig(alternative, remap bool) error {
	v := byte(0x02)
	if alternative {
		v |= 0x10
	}
	if remap {
		v |= 0x20
	}
	return d.command(ssd1305CmdCom, v)
}

// SetVcomh sets the VCOMH deselect level (only bits 5:2 used; reset 0x34).
func (d *SSD1305Full) SetVcomh(value uint8) error { return d.command(ssd1305CmdVcomh, value&0x3C) }

// SetAddressingMode selects the RAM addressing mode used by Show (reset: SSD1305AddrPage).
func (d *SSD1305Full) SetAddressingMode(mode SSD1305AddressingMode) error {
	if mode > SSD1305AddrPage {
		return ErrSSD1305Argument
	}
	d.mode = mode
	return d.command(ssd1305CmdAddrMode, byte(mode))
}

func ssd1305Interval(i uint8) byte {
	if i > 6 {
		i = 6
	}
	return i // table index 0..6 = 6, 32, 64, 128, 3, 4, 2 frames equals the register code
}

// SetScrollHorizontal sets up a horizontal scroll (start with ScrollStart). interval is a table index 0..6 =
// 6, 32, 64, 128, 3, 4, 2 frames per step, columns 1..4. Do not call while scrolling is active.
func (d *SSD1305Full) SetScrollHorizontal(right bool, startPage, endPage, interval, columns uint8) error {
	cmd := byte(0x27)
	if right {
		cmd = 0x26
	}
	return d.command(cmd, columns&7, startPage&7, ssd1305Interval(interval), endPage&7)
}

// SetScrollVertical sets up a vertical + horizontal scroll (columns 0..4, rows 1..63 per step).
func (d *SSD1305Full) SetScrollVertical(right bool, startPage, endPage, interval, columns, rows uint8) error {
	cmd := byte(0x2A)
	if right {
		cmd = 0x29
	}
	return d.command(cmd, columns&7, startPage&7, ssd1305Interval(interval), endPage&7, rows&0x3F)
}

// SetScrollArea sets the vertical scroll area: topFixed rows (0..63) fixed, scrollRows (1..64) scrolling.
func (d *SSD1305Full) SetScrollArea(topFixed, scrollRows uint8) error {
	return d.command(ssd1305CmdScrollArea, topFixed&0x3F, scrollRows&0x7F)
}

// ScrollStart activates the configured scroll (0x2F).
func (d *SSD1305Full) ScrollStart() error { return d.command(0x2F) }

// ScrollStop deactivates scrolling (0x2E) and rewrites the RAM from the framebuffer, as the datasheet requires.
func (d *SSD1305Full) ScrollStop() error {
	if err := d.command(0x2E); err != nil {
		return err
	}
	return d.showAll()
}

// SetAreaColor enables the area-colour mode and/or the low-power display mode (0xD8).
func (d *SSD1305Full) SetAreaColor(enable, lowPower bool) error {
	var v byte
	if enable {
		v |= 0x30
	}
	if lowPower {
		v |= 0x05
	}
	return d.command(ssd1305CmdAreaColor, v)
}

// SetBrightness sets the colour-bank brightness, 0..255 (reset 0x80).
func (d *SSD1305Full) SetBrightness(value uint8) error { return d.command(ssd1305CmdBrightness, value) }

// SetLUT sets the drive pulse width codes (31..63) of BANK0 and colours A, B, C.
func (d *SSD1305Full) SetLUT(bank0, a, b, c uint8) error {
	return d.command(ssd1305CmdLUT, bank0&0x3F, a&0x3F, b&0x3F, c&0x3F)
}

// SetBankColors assigns colours A..D (2 bits per bank) to banks 1..16 and, if banks17to32 is not nil, banks
// 17..32. Each group is 4 bytes, else ErrSSD1305Argument.
func (d *SSD1305Full) SetBankColors(banks1to16, banks17to32 []byte) error {
	if len(banks1to16) != 4 || (banks17to32 != nil && len(banks17to32) != 4) {
		return ErrSSD1305Argument
	}
	if err := d.command(append([]byte{ssd1305CmdBank1to16}, banks1to16...)...); err != nil {
		return err
	}
	if banks17to32 != nil {
		return d.command(append([]byte{ssd1305CmdBank17to32}, banks17to32...)...)
	}
	return nil
}

// ReadStatus reads the status byte (I2C only): bit 6 = 1 when the display is off; other bits reserved.
// It returns ErrSSD1305Unsupported on SPI.
func (d *SSD1305Full) ReadStatus() (byte, error) {
	if !d.bus.canRead() {
		return 0, ErrSSD1305Unsupported
	}
	return d.bus.readStatus()
}

// ReadPage reads n bytes (1..30) of display RAM from the given page and RAM column (I2C only); the dummy
// byte after the address change is discarded. It returns ErrSSD1305Unsupported on SPI.
func (d *SSD1305Full) ReadPage(page, column, n int) ([]byte, error) {
	if !d.bus.canRead() {
		return nil, ErrSSD1305Unsupported
	}
	if n < 1 || n > SSD1305I2CChunk-2 || page < 0 || page > 7 {
		return nil, ErrSSD1305Argument
	}
	if err := d.bus.writeCommands(ssd1305Address(page, column)); err != nil {
		return nil, err
	}
	return d.bus.readRAM(n)
}

// SendCommand sends raw bytes as one command stream.
func (d *SSD1305Full) SendCommand(b ...byte) error { return d.command(b...) }
