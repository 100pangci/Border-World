#!/usr/bin/env python3
"""竖直剖面渲染：沿一条线把方块画成 2D 剖面图（用于检查墙体的分层/镂空/高度）。

用法示例：
  python3 tools/slice_render.py --dir run/server --world world-demo2 \
      --axis x --fixed 0 --from 60 --to 300 --y-min 40 --y-max 320 \
      --out /tmp/opencode/bw-slice.png
"""
import argparse
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from world_compare import read_chunk  # noqa: E402

from PIL import Image  # noqa: E402

COLORS = {
    'minecraft:stone': (125, 125, 125),
    'minecraft:cobblestone': (110, 110, 110),
    'minecraft:deepslate': (77, 77, 80),
    'minecraft:dirt': (134, 96, 67),
    'minecraft:coarse_dirt': (122, 88, 62),
    'minecraft:rooted_dirt': (144, 104, 76),
    'minecraft:grass_block': (105, 165, 60),
    'minecraft:podzol': (92, 60, 26),
    'minecraft:sand': (219, 207, 163),
    'minecraft:red_sand': (190, 102, 33),
    'minecraft:sandstone': (216, 203, 155),
    'minecraft:gravel': (131, 127, 126),
    'minecraft:water': (63, 118, 228),
    'minecraft:lava': (255, 120, 20),
    'minecraft:bedrock': (55, 55, 55),
    'minecraft:andesite': (136, 136, 137),
    'minecraft:diorite': (188, 188, 190),
    'minecraft:granite': (149, 103, 85),
    'minecraft:tuff': (108, 109, 102),
    'minecraft:calcite': (224, 224, 220),
    'minecraft:snow_block': (240, 250, 250),
    'minecraft:snow': (240, 250, 250),
    'minecraft:ice': (160, 200, 240),
    'minecraft:clay': (160, 166, 179),
    'minecraft:moss_block': (89, 119, 45),
    'minecraft:oak_leaves': (60, 130, 45),
    'minecraft:oak_log': (110, 85, 50),
    'minecraft:short_grass': (110, 170, 70),
    'minecraft:tall_grass': (110, 170, 70),
    'minecraft:fern': (110, 170, 70),
    'minecraft:terracotta': (152, 94, 67),
    'minecraft:mud': (60, 60, 70),
}

FALLBACK = (255, 0, 255)


def color_for(name):
    if name in COLORS:
        return COLORS[name]
    if name.endswith('_leaves') or name.endswith('_log'):
        return (60, 130, 45) if name.endswith('_leaves') else (110, 85, 50)
    if name.endswith('_terracotta'):
        return (152, 94, 67)
    if name.endswith('_wool') or name.endswith('_concrete'):
        return (200, 200, 200)
    return None  # 非地形方块（空气/植物）按天空处理


def section_blocks(section):
    """返回 {(lx, ly, lz): block name}，只解码非空气的方块。"""
    bs = section.get('block_states')
    if bs is None:
        return {}
    palette = bs.get('palette', [])
    names = []
    for entry in palette:
        if hasattr(entry, 'get'):
            names.append(str(entry.get('Name', 'minecraft:air')))
        else:
            names.append(str(entry))
    if len(names) <= 1:
        return {}
    data = bs.get('data')
    if data is None:
        return {}
    longs = [int(v) & 0xFFFFFFFFFFFFFFFF for v in data]
    bits = max(4, (len(names) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    out = {}
    for idx in range(4096):
        long_index = idx // per_long
        if long_index >= len(longs):
            break
        offset = (idx % per_long) * bits
        value = (longs[long_index] >> offset) & mask
        if value >= len(names):
            continue
        name = names[value]
        if name == 'minecraft:air' or name == 'minecraft:cave_air' or name == 'minecraft:void_air':
            continue
        ly = idx >> 8
        lz = (idx >> 4) & 15
        lx = idx & 15
        out[(lx, ly, lz)] = name
    return out


def load_column(server_dir, world, cx, cz):
    """返回 {y: [(local_index, name)]} 的原始 section 列表。"""
    chunk = read_chunk(server_dir, world, cx, cz)
    if chunk is None:
        return None
    return chunk


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dir', required=True)
    parser.add_argument('--world', default='world')
    parser.add_argument('--axis', choices=['x', 'z'], default='x',
                        help='x = 固定 x 沿 z 切（侧面看墙沿 x 延伸时用 z 切）')
    parser.add_argument('--fixed', type=int, required=True)
    parser.add_argument('--from', dest='frm', type=int, required=True)
    parser.add_argument('--to', type=int, required=True)
    parser.add_argument('--y-min', type=int, default=40)
    parser.add_argument('--y-max', type=int, default=320)
    parser.add_argument('--out', required=True)
    parser.add_argument('--scale', type=int, default=1)
    args = parser.parse_args()

    length = args.to - args.frm + 1
    height = args.y_max - args.y_min + 1
    img = Image.new('RGB', (length, height), (140, 180, 240))
    px = img.load()

    cached = {}
    for u in range(length):
        coord = args.frm + u
        if args.axis == 'x':
            x, z = args.fixed, coord
        else:
            x, z = coord, args.fixed
        cx, cz = x >> 4, z >> 4
        key = (cx, cz)
        if key not in cached:
            cached[key] = load_column(args.dir, args.world, cx, cz)
        chunk = cached[key]
        if chunk is None:
            for i, y in enumerate(range(args.y_min, args.y_max + 1)):
                px[u, height - 1 - i] = (30, 30, 40)
            continue
        lx, lz = x & 15, z & 15
        sections = chunk.get('sections', [])
        # 每个 section 的 y 起点：1.18+ 用 section['Y']
        for section in sections:
            sy = int(section.get('Y', section.get('y', 0)))
            if sy * 16 + 15 < args.y_min or sy * 16 > args.y_max:
                continue
            blocks = cached.get(('sec', cx, cz, sy))
            if blocks is None:
                blocks = section_blocks(section)
                cached[('sec', cx, cz, sy)] = blocks
            for ly in range(16):
                y = sy * 16 + ly
                if y < args.y_min or y > args.y_max:
                    continue
                name = blocks.get((lx, ly, lz))
                if name is None:
                    continue
                color = color_for(name)
                if color is None:
                    continue
                px[u, height - 1 - (y - args.y_min)] = color

    if args.scale != 1:
        img = img.resize((length * args.scale, height * args.scale), Image.NEAREST)
    img.save(args.out)
    print(f'已写出 {args.out}: {img.width}x{img.height}（{args.axis}={args.fixed}, {args.frm}..{args.to}, y {args.y_min}..{args.y_max}）')


if __name__ == '__main__':
    main()
