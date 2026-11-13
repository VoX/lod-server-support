"""Screenshots without any imaging library.

Xvfb started with `-fbdir <dir>` keeps its framebuffer in `<dir>/Xvfb_screen0` as an XWD dump.
This module reads that dump and writes a PNG using only struct and zlib.
"""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

XWD_FIELDS = ('header_size', 'file_version', 'pixmap_format', 'pixmap_depth', 'pixmap_width',
              'pixmap_height', 'xoffset', 'byte_order', 'bitmap_unit', 'bitmap_bit_order',
              'bitmap_pad', 'bits_per_pixel', 'bytes_per_line', 'visual_class', 'red_mask',
              'green_mask', 'blue_mask', 'bits_per_rgb', 'colormap_entries', 'ncolors',
              'window_width', 'window_height', 'window_x', 'window_y', 'window_bdrwidth')
XWD_HEADER_BYTES = 4 * len(XWD_FIELDS)
XWD_COLOR_BYTES = 12
PNG_SIGNATURE = b'\x89PNG\r\n\x1a\n'


class XwdError(ValueError):
    """The framebuffer dump is not a format this reader handles."""


def parse_xwd_header(data: bytes) -> dict:
    """Decode the fixed XWD header (25 big-endian 32-bit fields)."""
    if len(data) < XWD_HEADER_BYTES:
        raise XwdError('framebuffer file is shorter than an XWD header')
    header = dict(zip(XWD_FIELDS, struct.unpack(f'>{len(XWD_FIELDS)}I', data[:XWD_HEADER_BYTES])))
    if header['file_version'] != 7:
        raise XwdError(f'unsupported XWD version {header["file_version"]}')
    if header['bits_per_pixel'] != 32:
        raise XwdError(f'unsupported pixel size {header["bits_per_pixel"]} (start Xvfb with depth 24)')
    return header


def channel_offset(mask: int, lsb_first: bool) -> int:
    """Which byte of a 4-byte pixel holds the channel with this 8-bit mask."""
    for shift in (0, 8, 16, 24):
        if mask == 0xFF << shift:
            return shift // 8 if lsb_first else 3 - shift // 8
    raise XwdError(f'unsupported colour mask {mask:#x}')


def xwd_to_rgb(data: bytes) -> tuple[int, int, bytes]:
    """Convert an XWD dump to (width, height, packed RGB bytes)."""
    header = parse_xwd_header(data)
    width, height = header['pixmap_width'], header['pixmap_height']
    stride = header['bytes_per_line']
    start = header['header_size'] + header['ncolors'] * XWD_COLOR_BYTES
    if len(data) < start + stride * height:
        raise XwdError('framebuffer file is shorter than its header says')
    lsb_first = header['byte_order'] == 0
    offsets = [channel_offset(header[name], lsb_first)
               for name in ('red_mask', 'green_mask', 'blue_mask')]
    rgb = bytearray(width * height * 3)
    for row in range(height):
        line = data[start + row * stride:start + row * stride + width * 4]
        base = row * width * 3
        for channel, offset in enumerate(offsets):
            rgb[base + channel:base + width * 3:3] = line[offset::4]
    return width, height, bytes(rgb)


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    """One PNG chunk: length, type, data, CRC."""
    return (struct.pack('>I', len(payload)) + kind + payload
            + struct.pack('>I', zlib.crc32(kind + payload) & 0xFFFFFFFF))


def encode_png(width: int, height: int, rgb: bytes) -> bytes:
    """Encode packed 8-bit RGB as a PNG file."""
    if len(rgb) != width * height * 3:
        raise XwdError('pixel data does not match the picture size')
    rows = bytearray()
    for row in range(height):
        rows.append(0)
        rows += rgb[row * width * 3:(row + 1) * width * 3]
    header = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    return (PNG_SIGNATURE + png_chunk(b'IHDR', header)
            + png_chunk(b'IDAT', zlib.compress(bytes(rows), 6)) + png_chunk(b'IEND', b''))


def distinct_colours(rgb: bytes, step: int = 97) -> int:
    """How many different colours a sparse sample of the picture contains (information only)."""
    return len({rgb[index:index + 3] for index in range(0, len(rgb) - 2, 3 * step)})


def capture(framebuffer: Path, dest: Path) -> dict:
    """Write the current framebuffer as a PNG and describe what was written."""
    width, height, rgb = xwd_to_rgb(Path(framebuffer).read_bytes())
    png = encode_png(width, height, rgb)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(png)
    return {'path': str(dest), 'width': width, 'height': height, 'bytes': len(png),
            'distinct_colours': distinct_colours(rgb)}
