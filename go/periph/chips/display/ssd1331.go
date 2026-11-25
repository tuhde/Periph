package display

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SSD1331 panel geometry.
const (
	SSD1331Width      = 96
	SSD1331Height     = 64
	SSD1331BufferSize = SSD1331Width * SSD1331Height * 2
)

// SSD1331 RGB565 colours.
const (
	SSD1331Black   uint16 = 0x0000
	SSD1331White   uint16 = 0xFFFF
	SSD1331Red     uint16 = 0xF800
	SSD1331Green   uint16 = 0x07E0
	SSD1331Blue    uint16 = 0x001F
	SSD1331Yellow  uint16 = 0xFFE0
	SSD1331Cyan    uint16 = 0x07FF
	SSD1331Magenta uint16 = 0xF81F
)

// SSD1331 display modes for SetDisplayMode.
const (
	SSD1331ModeNormal  uint8 = 0xA4
	SSD1331ModeAllOn   uint8 = 0xA5
	SSD1331ModeAllOff  uint8 = 0xA6
	SSD1331ModeInverse uint8 = 0xA7
)

const (
	ssd1331CmdColumn        uint8 = 0x15
	ssd1331CmdRow           uint8 = 0x75
	ssd1331CmdContrastA     uint8 = 0x81
	ssd1331CmdContrastB     uint8 = 0x82
	ssd1331CmdContrastC     uint8 = 0x83
	ssd1331CmdMasterCurrent uint8 = 0x87
	ssd1331CmdPrechargeA    uint8 = 0x8A
	ssd1331CmdPrechargeB    uint8 = 0x8B
	ssd1331CmdPrechargeC    uint8 = 0x8C
	ssd1331CmdRemap         uint8 = 0xA0
	ssd1331CmdStartLine     uint8 = 0xA1
	ssd1331CmdOffset        uint8 = 0xA2
	ssd1331CmdModeNormal    uint8 = 0xA4
	ssd1331CmdMux           uint8 = 0xA8
	ssd1331CmdDimSetting    uint8 = 0xAB
	ssd1331CmdMasterConfig  uint8 = 0xAD
	ssd1331CmdDisplayDim    uint8 = 0xAC
	ssd1331CmdDisplayOff    uint8 = 0xAE
	ssd1331CmdDisplayOn     uint8 = 0xAF
	ssd1331CmdPowerSave     uint8 = 0xB0
	ssd1331CmdPhase         uint8 = 0xB1
	ssd1331CmdClock         uint8 = 0xB3
	ssd1331CmdGrayLinear    uint8 = 0xB9
	ssd1331CmdPrechargeLvl  uint8 = 0xBB
	ssd1331CmdVCOMH         uint8 = 0xBE
	ssd1331CmdLine          uint8 = 0x21
	ssd1331CmdRect          uint8 = 0x22
	ssd1331CmdCopy          uint8 = 0x23
	ssd1331CmdDim           uint8 = 0x24
	ssd1331CmdClear         uint8 = 0x25
	ssd1331CmdFill          uint8 = 0x26
	ssd1331CmdScrollSetup   uint8 = 0x27
	ssd1331CmdScrollOff     uint8 = 0x2E
	ssd1331CmdScrollOn      uint8 = 0x2F
)

// SSD1331RGB packs 8-bit R, G, B into RGB565.
func SSD1331RGB(r, g, b uint8) uint16 {
	return uint16(r&0xF8)<<8 | uint16(g&0xFC)<<3 | uint16(b>>3)
}

// ssd1331ColorOps converts RGB565 to the three 5/6/5-bit colour operands
// (C = red, B = green, A = blue).
func ssd1331ColorOps(c uint16) [3]byte {
	return [3]byte{byte((c>>11)&0x1F) << 1, byte((c >> 5) & 0x3F), byte(c&0x1F) << 1}
}

// SSD1331Minimal drives an SSD1331 96 x 64 RGB OLED controller (Waveshare
// 0.95inch RGB OLED) over 4-wire SPI.
//
// The picture is kept in a 96 x 64 RGB565 framebuffer (12 288 bytes, high byte
// first) and sent to the display RAM by Show().
//
// The connection is a connection.DisplayConn with no BUSY pin (see
// specs/transport_display.md). The SSD1331 takes command parameters with D/C#
// low, so the driver sends every byte of a command as its own
// SendCommand(byte, nil); only pixel data uses SendData.
type SSD1331Minimal struct {
	conn  connection.DisplayConn
	fb    []byte
	remap uint8
}

// NewSSD1331Minimal runs the vendor initialisation sequence: hardware reset
// (100 ms settle), display off, contrast FF FF FF, master current 7/16, 65k
// colours with column remap and reversed COM scan, VCC external, power save
// off, 100 ms VCC wait, scroll off, display RAM cleared, display on. The
// framebuffer is cleared to black.
func NewSSD1331Minimal(conn connection.DisplayConn) (*SSD1331Minimal, error) {
	d := &SSD1331Minimal{conn: conn, fb: make([]byte, SSD1331BufferSize), remap: 0x72}
	if err := d.initSequence(); err != nil {
		return nil, err
	}
	return d, nil
}

// Width returns the panel width in pixels (96).
func (d *SSD1331Minimal) Width() int { return SSD1331Width }

// Height returns the panel height in pixels (64).
func (d *SSD1331Minimal) Height() int { return SSD1331Height }

// Clear fills the framebuffer with one RGB565 colour. The display is unchanged
// until Show().
func (d *SSD1331Minimal) Clear(color uint16) {
	hi, lo := byte(color>>8), byte(color)
	for i := 0; i < SSD1331BufferSize; i += 2 {
		d.fb[i] = hi
		d.fb[i+1] = lo
	}
}

// SetPixel sets one framebuffer pixel; out-of-range coordinates are ignored.
func (d *SSD1331Minimal) SetPixel(x, y int, color uint16) {
	if x < 0 || y < 0 || x >= SSD1331Width || y >= SSD1331Height {
		return
	}
	i := (y*SSD1331Width + x) * 2
	d.fb[i] = byte(color >> 8)
	d.fb[i+1] = byte(color)
}

// Show sends the whole framebuffer to the display RAM: window 0..95 x 0..63
// and 12 288 bytes in 192-byte (one row) transfers.
func (d *SSD1331Minimal) Show() error {
	if err := d.window(0, 0, SSD1331Width-1, SSD1331Height-1); err != nil {
		return err
	}
	for off := 0; off < SSD1331BufferSize; off += 2 * SSD1331Width {
		if err := d.conn.SendData(d.fb[off : off+2*SSD1331Width]); err != nil {
			return err
		}
	}
	return nil
}

// DisplayOn switches the display on (0xAF); the RAM content is shown.
func (d *SSD1331Minimal) DisplayOn() error { return d.cmd(ssd1331CmdDisplayOn) }

// DisplayOff switches the display off (0xAE); the RAM content is kept.
func (d *SSD1331Minimal) DisplayOff() error { return d.cmd(ssd1331CmdDisplayOff) }

// cmd sends every byte as its own command (all with D/C# low).
func (d *SSD1331Minimal) cmd(bytes ...uint8) error {
	for _, b := range bytes {
		if err := d.conn.SendCommand(b, nil); err != nil {
			return err
		}
	}
	return nil
}

func (d *SSD1331Minimal) window(x0, y0, x1, y1 uint8) error {
	return d.cmd(ssd1331CmdColumn, x0, x1, ssd1331CmdRow, y0, y1)
}

func (d *SSD1331Minimal) initSequence() error {
	if err := d.conn.Reset(); err != nil {
		return err
	}
	d.conn.Delay(100 * time.Millisecond)
	steps := [][]uint8{
		{ssd1331CmdDisplayOff},
		{ssd1331CmdContrastA, 0xFF, ssd1331CmdContrastB, 0xFF, ssd1331CmdContrastC, 0xFF},
		{ssd1331CmdMasterCurrent, 0x06},
		{ssd1331CmdPrechargeA, 0x64, ssd1331CmdPrechargeB, 0x78, ssd1331CmdPrechargeC, 0x64},
		{ssd1331CmdRemap, d.remap},
		{ssd1331CmdStartLine, 0x00, ssd1331CmdOffset, 0x00},
		{ssd1331CmdModeNormal},
		{ssd1331CmdMux, 0x3F, ssd1331CmdMasterConfig, 0x8E},
		{ssd1331CmdPowerSave, 0x0B, ssd1331CmdPhase, 0x31, ssd1331CmdClock, 0xF0},
		{ssd1331CmdPrechargeLvl, 0x3A, ssd1331CmdVCOMH, 0x3E},
		{ssd1331CmdScrollOff},
		{ssd1331CmdClear, 0x00, 0x00, 0x5F, 0x3F},
	}
	for _, s := range steps {
		if err := d.cmd(s...); err != nil {
			return err
		}
	}
	d.conn.Delay(100 * time.Millisecond)
	return d.cmd(ssd1331CmdDisplayOn)
}

// SSD1331Full extends SSD1331Minimal with software drawing into the
// framebuffer, partial transfers, the controller's graphic acceleration
// commands (line, rectangle with fill, copy, dim and clear window, scrolling —
// these act on the display RAM only, not on the host framebuffer), and the
// electrical settings (contrast, master current, flip, display mode, power
// save, dim mode, gray table).
type SSD1331Full struct {
	*SSD1331Minimal
}

// NewSSD1331Full runs the initialisation sequence of NewSSD1331Minimal.
func NewSSD1331Full(conn connection.DisplayConn) (*SSD1331Full, error) {
	m, err := NewSSD1331Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &SSD1331Full{SSD1331Minimal: m}, nil
}

// GetPixel returns the RGB565 colour of one framebuffer pixel (0 when out of range).
func (d *SSD1331Full) GetPixel(x, y int) uint16 {
	if x < 0 || y < 0 || x >= SSD1331Width || y >= SSD1331Height {
		return 0
	}
	i := (y*SSD1331Width + x) * 2
	return uint16(d.fb[i])<<8 | uint16(d.fb[i+1])
}

func ssd1331Abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// DrawLine draws a Bresenham line into the framebuffer.
func (d *SSD1331Full) DrawLine(x0, y0, x1, y1 int, color uint16) {
	dx := ssd1331Abs(x1 - x0)
	dy := -ssd1331Abs(y1 - y0)
	sx, sy := 1, 1
	if x0 >= x1 {
		sx = -1
	}
	if y0 >= y1 {
		sy = -1
	}
	err := dx + dy
	for {
		d.SetPixel(x0, y0, color)
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

// DrawRect draws a rectangle outline, or a filled rectangle, into the framebuffer.
func (d *SSD1331Full) DrawRect(x, y, w, h int, color uint16, fill bool) {
	if w <= 0 || h <= 0 {
		return
	}
	for j := 0; j < h; j++ {
		for i := 0; i < w; i++ {
			if fill || i == 0 || j == 0 || i == w-1 || j == h-1 {
				d.SetPixel(x+i, y+j, color)
			}
		}
	}
}

// Blit copies a row-major w x h RGB565 bitmap into the framebuffer.
func (d *SSD1331Full) Blit(x, y int, pixels []uint16, w, h int) {
	for j := 0; j < h; j++ {
		for i := 0; i < w; i++ {
			if k := j*w + i; k < len(pixels) {
				d.SetPixel(x+i, y+j, pixels[k])
			}
		}
	}
}

// ShowRegion sends only a rectangle of the framebuffer (one window, row by
// row). The region is clipped to the panel; an empty region sends nothing.
func (d *SSD1331Full) ShowRegion(x, y, w, h int) error {
	if x < 0 {
		w += x
		x = 0
	}
	if y < 0 {
		h += y
		y = 0
	}
	if x+w > SSD1331Width {
		w = SSD1331Width - x
	}
	if y+h > SSD1331Height {
		h = SSD1331Height - y
	}
	if w <= 0 || h <= 0 {
		return nil
	}
	if err := d.window(uint8(x), uint8(y), uint8(x+w-1), uint8(y+h-1)); err != nil {
		return err
	}
	for j := 0; j < h; j++ {
		start := ((y+j)*SSD1331Width + x) * 2
		if err := d.conn.SendData(d.fb[start : start+w*2]); err != nil {
			return err
		}
	}
	return nil
}

// HwLine draws a hardware line (0x21) on the display RAM; coordinates 0..95 / 0..63.
func (d *SSD1331Full) HwLine(x0, y0, x1, y1 uint8, color uint16) error {
	c := ssd1331ColorOps(color)
	return d.cmd(ssd1331CmdLine, x0, y0, x1, y1, c[0], c[1], c[2])
}

// HwRect draws a hardware rectangle (0x26 fill enable + 0x22) on the display
// RAM. w and h must be at least 1 (otherwise nothing is sent).
func (d *SSD1331Full) HwRect(x, y, w, h uint8, lineColor, fillColor uint16, fill bool) error {
	if w == 0 || h == 0 {
		return nil
	}
	f := uint8(0)
	if fill {
		f = 1
	}
	if err := d.cmd(ssd1331CmdFill, f); err != nil {
		return err
	}
	l, fc := ssd1331ColorOps(lineColor), ssd1331ColorOps(fillColor)
	return d.cmd(ssd1331CmdRect, x, y, x+w-1, y+h-1, l[0], l[1], l[2], fc[0], fc[1], fc[2])
}

// HwClear clears a window of the display RAM (0x25).
func (d *SSD1331Full) HwClear(x0, y0, x1, y1 uint8) error {
	return d.cmd(ssd1331CmdClear, x0, y0, x1, y1)
}

// HwDim dims a window of the display RAM (0x24).
func (d *SSD1331Full) HwDim(x0, y0, x1, y1 uint8) error {
	return d.cmd(ssd1331CmdDim, x0, y0, x1, y1)
}

// HwCopy copies a window of the display RAM to (x, y) (0x23).
func (d *SSD1331Full) HwCopy(x0, y0, x1, y1, x, y uint8) error {
	return d.cmd(ssd1331CmdCopy, x0, y0, x1, y1, x, y)
}

// Scroll configures and starts continuous scrolling (0x27, 0x2F): horizontal
// columns per step 0..95, startRow, rows, vertical rows per step 0..63,
// interval 0 = 6, 1 = 10, 2 = 100, 3 = 200 frames per step.
func (d *SSD1331Full) Scroll(horizontal, startRow, rows, vertical, interval uint8) error {
	return d.cmd(ssd1331CmdScrollSetup, horizontal, startRow, rows, vertical, interval&3, ssd1331CmdScrollOn)
}

// ScrollStop stops scrolling (0x2E). The display RAM must be rewritten afterwards.
func (d *SSD1331Full) ScrollStop() error { return d.cmd(ssd1331CmdScrollOff) }

// SetContrast sets the contrast of colour channels A, B, C (0x81..0x83), 0..255.
func (d *SSD1331Full) SetContrast(a, b, c uint8) error {
	return d.cmd(ssd1331CmdContrastA, a, ssd1331CmdContrastB, b, ssd1331CmdContrastC, c)
}

// SetMasterCurrent sets the master current (0x87) to n/16 for n = 1..16 (clamped).
func (d *SSD1331Full) SetMasterCurrent(n uint8) error {
	if n < 1 {
		n = 1
	}
	if n > 16 {
		n = 16
	}
	return d.cmd(ssd1331CmdMasterCurrent, n-1)
}

// SetDisplayMode selects SSD1331ModeNormal, SSD1331ModeAllOn, SSD1331ModeAllOff
// or SSD1331ModeInverse.
func (d *SSD1331Full) SetDisplayMode(mode uint8) error { return d.cmd(mode) }

// SetRemap writes the raw remap and colour depth byte (0xA0).
func (d *SSD1331Full) SetRemap(flags uint8) error {
	d.remap = flags
	return d.cmd(ssd1331CmdRemap, flags)
}

// SetFlip mirrors horizontally (column remap, bit 1) and/or vertically (COM scan, bit 4).
func (d *SSD1331Full) SetFlip(horizontal, vertical bool) error {
	r := d.remap &^ 0x12
	if horizontal {
		r |= 0x02
	}
	if vertical {
		r |= 0x10
	}
	return d.SetRemap(r)
}

// SetPowerSave sets the power save mode (0xB0): true = 0x1A enable, false = 0x0B disable.
func (d *SSD1331Full) SetPowerSave(on bool) error {
	v := uint8(0x0B)
	if on {
		v = 0x1A
	}
	return d.cmd(ssd1331CmdPowerSave, v)
}

// DimMode sets the dim mode (0xAB + 0xAC): contrasts A/B/C and pre-charge voltage 0..31.
func (d *SSD1331Full) DimMode(a, b, c, precharge uint8) error {
	return d.cmd(ssd1331CmdDimSetting, 0x00, a, b, c, precharge&0x1F, ssd1331CmdDisplayDim)
}

// GrayLinear restores the linear gray scale table (0xB9).
func (d *SSD1331Full) GrayLinear() error { return d.cmd(ssd1331CmdGrayLinear) }

// SendCommand sends any command with parameters (all with D/C# low).
func (d *SSD1331Full) SendCommand(command uint8, params ...uint8) error {
	return d.cmd(append([]uint8{command}, params...)...)
}
