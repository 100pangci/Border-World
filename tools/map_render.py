#!/usr/bin/env python3
"""俯视图渲染：按地表方块上色（近旧版地图观感），用于对照旧版 Far Lands 地图。

用法：
  python3 tools/map_render.py --dir run/server --world world-x --chunks -2 4 17 17 \
      --out /tmp/opencode/map.png --scale 1
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from world_compare import read_chunk  # noqa: E402
from slice_render import section_blocks  # noqa: E402

from PIL import Image  # noqa: E402

COLORS = {
    'minecraft:grass_block': (95, 159, 53),
    'minecraft:water': (63, 118, 228),
    'minecraft:sand': (219, 207, 163),
    'minecraft:red_sand': (190, 102, 33),
    'minecraft:sandstone': (216, 203, 155),
    'minecraft:stone': (125, 125, 125),
    'minecraft:deepslate': (77, 77, 80),
    'minecraft:dirt': (134, 96, 67),
    'minecraft:gravel': (131, 127, 126),
    'minecraft:snow_block': (240, 250, 250),
    'minecraft:snow': (240, 250, 250),
    'minecraft:ice': (160, 200, 240),
    'minecraft:podzol': (92, 60, 26),
    'minecraft:coarse_dirt': (122, 88, 62),
    'minecraft:moss_block': (89, 119, 45),
    'minecraft:clay': (160, 166, 179),
    'minecraft:lava': (255, 120, 20),
    'minecraft:bedrock': (55, 55, 55),
    'minecraft:andesite': (136, 136, 137),
    'minecraft:tuff': (108, 109, 102),
    'minecraft:calcite': (224, 224, 220),
    'minecraft:terracotta': (152, 94, 67),
}


def color_for(name):
    if name in COLORS:
        return COLORS[name]
    if name.endswith('_leaves'):
        return (60, 130, 45)
    if name.endswith('_log') or name.endswith('_wood'):
        return (110, 85, 50)
    if name.startswith('minecraft:snow'):
        return (240, 250, 250)
    if name.endswith('_terracotta'):
        return (152, 94, 67)
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dir', required=True)
    parser.add_argument('--world', default='world')
    parser.add_argument('--chunks', nargs='+', type=int, required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--scale', type=int, default=1)
    parser.add_argument('--y-max', type=int, default=320)
    args = parser.parse_args()

    flat = args.chunks
    rects = [tuple(flat[i:i + 4]) for i in range(0, len(flat), 4)]
    min_x = min(min(r[0], r[2]) for r in rects) * 16
    max_x = max(max(r[0], r[2]) for r in rects) * 16 + 15
    min_z = min(min(r[1], r[3]) for r in rects) * 16
    max_z = max(max(r[1], r[3]) for r in rects) * 16 + 15
    width = (max_x - min_x + 1) // args.scale
    height = (max_z - min_z + 1) // args.scale
    img = Image.new('RGB', (width, height), (20, 20, 30))
    px = img.load()

    cache = {}
    for iz in range(height):
        z = min_z + iz * args.scale
        for ix in range(width):
            x = min_x + ix * args.scale
            cx, cz = x >> 4, z >> 4
            key = (cx, cz)
            if key not in cache:
                cache[key] = read_chunk(args.dir, args.world, cx, cz)
            chunk = cache[key]
            if chunk is None:
                continue
            if ('sec', cx, cz) not in cache:
                cache[('sec', cx, cz)] = {
                    int(sec['Y']): section_blocks(sec) for sec in chunk.get('sections', [])
                }
            sections = cache[('sec', cx, cz)]
            lx, lz = x & 15, z & 15
            color = None
            for sy in sorted(sections, reverse=True):
                blocks = sections[sy]
                for ly in range(15, -1, -1):
                    y = sy * 16 + ly
                    if y > args.y_max:
                        continue
                    name = blocks.get((lx, ly, lz))
                    if name is None:
                        continue
                    color = color_for(name)
                    if color is not None:
                        break
                if color is not None:
                    break
            if color is None:
                color = (200, 200, 200)
            px[ix, iz] = color

    if args.scale != 1:
        img = img.resize((width, height), Image.NEAREST)
    img.save(args.out)
    print(f'已写出 {args.out}: {img.width}x{img.height}, X[{min_x},{max_x}] Z[{min_z},{max_z}]')


if __name__ == '__main__':
    main()
