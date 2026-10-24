#!/usr/bin/env python3
"""Generate 100,000 deterministic ColorPuzzle levels."""

from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "levels.dat"

MAX_LEVEL = 100_000
SEED_BASE = 20261024


def board_size(level: int) -> tuple[int, int]:
    """Gradually increase board dimensions from 4x4 to 10x10."""
    stage = (max(1, level) - 1) // 100

    for first in range(4, 11):
        stages_in_row = 11 - first

        if stage < stages_in_row:
            return first, first + stage

        stage -= stages_in_row

    return 10, 10


def density_for_level(level: int) -> float:
    """Make the first 1,000 levels medium difficulty, not easy."""
    if level <= 1_000:
        # Medium: 60–70% occupied cells.
        return 0.60 + 0.10 * ((level - 1) / 999)

    if level <= 10_000:
        # Above medium: 70–80%.
        return 0.70 + 0.10 * ((level - 1_001) / 8_999)

    if level <= 50_000:
        # Hard: 80–88%.
        return 0.80 + 0.08 * ((level - 10_001) / 39_999)

    if level <= 90_000:
        # Very hard: 88–94%.
        return 0.88 + 0.06 * ((level - 50_001) / 39_999)

    # Extreme: 94–97%, capped so at least one empty cell remains.
    return 0.94 + 0.03 * ((level - 90_001) / 9_999)


def generate_level(level: int) -> str:
    cols, rows = board_size(level)
    total = cols * rows

    density = density_for_level(level)
    target = int(total * density)
    target = max(1, min(total - 1, target))

    rng = random.Random(SEED_BASE + level * 1009)

    # Compatibility with the existing game:
    # every occupied cell in a column has the same U or D direction.
    # This gives a predictable removal order under the existing rules.
    directions = [
        rng.choice(("U", "D"))
        for _ in range(cols)
    ]

    cells = ["."] * total
    positions = list(range(total))
    rng.shuffle(positions)

    for index in positions[:target]:
        x = index % cols
        cells[index] = directions[x]

    grid = "".join(cells)

    return f"{level}|{cols}|{rows}|{grid}"


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)

    with OUTPUT.open("w", encoding="ascii", newline="\n") as file:
        for level in range(1, MAX_LEVEL + 1):
            file.write(generate_level(level) + "\n")

    print(f"Generated {MAX_LEVEL} levels: {OUTPUT}")
    print(f"Output size: {OUTPUT.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
