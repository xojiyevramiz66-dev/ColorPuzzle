#!/usr/bin/env python3

from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "levels.dat"

MAX_LEVEL = 100_000
SEED_BASE = 20261024

# U/D/L/R — прямые стрелки.
# V/B — вертикальная и горизонтальная двунаправленные.
# Q/W — две изогнутые стрелки.
SYMBOLS = ("U", "D", "L", "R", "V", "B", "Q", "W")


def board_size(level: int) -> tuple[int, int]:
    stage = (max(1, level) - 1) // 100

    for first in range(4, 11):
        stages_in_row = 11 - first
        if stage < stages_in_row:
            return first, first + stage
        stage -= stages_in_row

    return 10, 10


def density_for_level(level: int) -> float:
    if level <= 1_000:
        return 0.60 + 0.10 * ((level - 1) / 999)
    if level <= 10_000:
        return 0.70 + 0.10 * ((level - 1_001) / 8_999)
    if level <= 50_000:
        return 0.80 + 0.08 * ((level - 10_001) / 39_999)
    if level <= 90_000:
        return 0.88 + 0.06 * ((level - 50_001) / 39_999)
    return 0.94 + 0.03 * ((level - 90_001) / 9_999)


def generate_level(level: int) -> str:
    cols, rows = board_size(level)
    total = cols * rows
    rng = random.Random(SEED_BASE + level * 1009)

    target = int(total * density_for_level(level))
    target = max(1, min(total - 1, target))

    cells = ["."] * total
    positions = list(range(total))
    rng.shuffle(positions)

    for index in positions[:target]:
        cells[index] = rng.choice(SYMBOLS)

    return f"{level}|{cols}|{rows}|{''.join(cells)}"


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)

    with OUTPUT.open("w", encoding="ascii", newline="\n") as file:
        for level in range(1, MAX_LEVEL + 1):
            file.write(generate_level(level) + "\n")

    print(f"Generated {MAX_LEVEL} levels")
    print(f"File: {OUTPUT}")
    print(f"Size: {OUTPUT.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
