
#!/usr/bin/env python3
"""Generate 10,000 deterministic, solvable ColorPuzzle levels."""

from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "levels.dat"
MAX_LEVEL = 10_000


def board_size(level: int) -> tuple[int, int]:
    stage = (max(1, level) - 1) // 5

    for first in range(4, 11):
        stages_in_row = 11 - first

        if stage < stages_in_row:
            return first, first + stage

        stage -= stages_in_row

    return 10, 10


def generate_level(level: int) -> str:
    cols, rows = board_size(level)
    total = cols * rows

    density = 0.45 + min((level - 1) / 80.0, 1.0) * 0.45
    target = min(total - 1, int(total * density))
    target = max(1, target)

    rng = random.Random(20261024 + level * 1009)

    # Каждый столбец использует одно вертикальное направление.
    # Это гарантирует возможность убрать блоки по порядку.
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


if __name__ == "__main__":
    main()
