
#!/usr/bin/env python3
from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "levels.dat"

MAX_LEVEL = 100_000
SEED_BASE = 20261024

SYMBOL_DIRECTIONS = {
    "U": ((0, -1),),
    "D": ((0, 1),),
    "L": ((-1, 0),),
    "R": ((1, 0),),
    "V": ((0, -1), (0, 1)),
    "B": ((-1, 0), (1, 0)),
    "Q": ((-1, -1), (1, 1)),  # ↴
    "W": ((1, -1), (-1, 1)),  # ↵
}
SYMBOLS = tuple(SYMBOL_DIRECTIONS)
ALL_DIRECTIONS = tuple(dict.fromkeys(
    d for directions in SYMBOL_DIRECTIONS.values() for d in directions
))


def board_size(level):
    stage = (max(1, level) - 1) // 100
    for first in range(4, 11):
        stages = 11 - first
        if stage < stages:
            return first, first + stage
        stage -= stages
    return 10, 10


def density_for_level(level):
    if level <= 1_000:
        return 0.60 + 0.10 * ((level - 1) / 999)
    if level <= 10_000:
        return 0.70 + 0.10 * ((level - 1_001) / 8_999)
    if level <= 50_000:
        return 0.80 + 0.08 * ((level - 10_001) / 39_999)
    if level <= 90_000:
        return 0.88 + 0.06 * ((level - 50_001) / 39_999)
    return 0.94 + 0.03 * ((level - 90_001) / 9_999)


def ray_clear(x, y, direction, occupied, cols, rows):
    dx, dy = direction
    x += dx
    y += dy
    while 0 <= x < cols and 0 <= y < rows:
        if (x, y) in occupied:
            return False
        x += dx
        y += dy
    return True


def generate_level(level):
    cols, rows = board_size(level)
    total = cols * rows
    rng = random.Random(SEED_BASE + level * 1009)

    target = max(1, min(total - 1, int(total * density_for_level(level))))
    positions = [(i % cols, i // cols) for i in range(total)]
    rng.shuffle(positions)
    selected = positions[:target]
    remaining = set(selected)

    by_x = [set() for _ in range(cols)]
    by_y = [set() for _ in range(rows)]
    count_x = [0] * cols
    count_y = [0] * rows

    for x, y in selected:
        by_x[x].add((x, y))
        by_y[y].add((x, y))
        count_x[x] += 1
        count_y[y] += 1

    min_x = min(i for i, n in enumerate(count_x) if n)
    max_x = max(i for i, n in enumerate(count_x) if n)
    min_y = min(i for i, n in enumerate(count_y) if n)
    max_y = max(i for i, n in enumerate(count_y) if n)

    grid = ["."] * total

    while remaining:
        sides = []
        if count_x[min_x]:
            sides.append(("x", min_x))
        if count_x[max_x]:
            sides.append(("x", max_x))
        if count_y[min_y]:
            sides.append(("y", min_y))
        if count_y[max_y]:
            sides.append(("y", max_y))

        axis, edge = rng.choice(sides)
        edge_set = by_x[edge] if axis == "x" else by_y[edge]
        x, y = rng.choice(tuple(edge_set))

        free = {
            d for d in ALL_DIRECTIONS
            if ray_clear(x, y, d, remaining, cols, rows)
        }
        choices = [
            symbol for symbol, dirs in SYMBOL_DIRECTIONS.items()
            if any(d in free for d in dirs)
        ]
        grid[y * cols + x] = rng.choice(choices)

        remaining.remove((x, y))
        by_x[x].remove((x, y))
        by_y[y].remove((x, y))
        count_x[x] -= 1
        count_y[y] -= 1

        while min_x < cols and count_x[min_x] == 0:
            min_x += 1
        while max_x >= 0 and count_x[max_x] == 0:
            max_x -= 1
        while min_y < rows and count_y[min_y] == 0:
            min_y += 1
        while max_y >= 0 and count_y[max_y] == 0:
            max_y -= 1

    return f"{level}|{cols}|{rows}|{''.join(grid)}"


def main():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", encoding="ascii", newline="\n") as file:
        for level in range(1, MAX_LEVEL + 1):
            file.write(generate_level(level) + "\n")
    print(f"Generated {MAX_LEVEL} levels: {OUTPUT}")


if __name__ == "__main__":
    main()
