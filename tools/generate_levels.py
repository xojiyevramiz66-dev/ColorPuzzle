#!/usr/bin/env python3

from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "levels.dat"

MAX_LEVEL = 100_000
SEED_BASE = 20261024

# Направления, совместимые с логикой Android-игры.
SYMBOL_DIRECTIONS = {
    "U": ((0, -1),),
    "D": ((0, 1),),
    "L": ((-1, 0),),
    "R": ((1, 0),),
    "V": ((0, -1), (0, 1)),
    "B": ((-1, 0), (1, 0)),
}

ALL_DIRECTIONS = tuple(
    dict.fromkeys(
        direction
        for directions in SYMBOL_DIRECTIONS.values()
        for direction in directions
    )
)


def board_size(level: int) -> tuple[int, int]:
    """Размеры доски соответствуют calculateBoardSize() в Kotlin."""
    stage = (max(1, level) - 1) // 5

    for first in range(4, 11):
        stages_in_row = 11 - first

        if stage < stages_in_row:
            return first, first + stage

        stage -= stages_in_row

    return 10, 10


def density_for_level(level: int) -> float:
    """Плотность блоков увеличивается по мере прохождения игры."""
    if level <= 1_000:
        return 0.60 + 0.10 * ((level - 1) / 999)

    if level <= 10_000:
        return 0.70 + 0.10 * ((level - 1_001) / 8_999)

    if level <= 50_000:
        return 0.80 + 0.08 * ((level - 10_001) / 39_999)

    if level <= 90_000:
        return 0.88 + 0.06 * ((level - 50_001) / 39_999)

    return 0.94 + 0.03 * ((level - 90_001) / 9_999)


def ray_clear(
    x: int,
    y: int,
    direction: tuple[int, int],
    occupied: set[tuple[int, int]],
    cols: int,
    rows: int,
) -> bool:
    """Проверяет, свободен ли путь до границы доски."""
    dx, dy = direction
    x += dx
    y += dy

    while 0 <= x < cols and 0 <= y < rows:
        if (x, y) in occupied:
            return False

        x += dx
        y += dy

    return True


def generate_level(level: int) -> str:
    cols, rows = board_size(level)
    total = cols * rows

    rng = random.Random(SEED_BASE + level * 1009)

    target = max(
        1,
        min(total - 1, int(total * density_for_level(level))),
    )

    positions = [
        (index % cols, index // cols)
        for index in range(total)
    ]
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

    min_x = min(x for x, count in enumerate(count_x) if count)
    max_x = max(x for x, count in enumerate(count_x) if count)
    min_y = min(y for y, count in enumerate(count_y) if count)
    max_y = max(y for y, count in enumerate(count_y) if count)

    grid = ["."] * total

    while remaining:
        edges = []

        if count_x[min_x] > 0:
            edges.append(("x", min_x))

        if max_x != min_x and count_x[max_x] > 0:
            edges.append(("x", max_x))

        if count_y[min_y] > 0:
            edges.append(("y", min_y))

        if max_y != min_y and count_y[max_y] > 0:
            edges.append(("y", max_y))

        if not edges:
            raise RuntimeError(
                f"Не удалось найти крайний блок уровня {level}"
            )

        axis, edge = rng.choice(edges)
        edge_set = by_x[edge] if axis == "x" else by_y[edge]

        x, y = rng.choice(tuple(edge_set))

        free_directions = [
            direction
            for direction in ALL_DIRECTIONS
            if ray_clear(
                x,
                y,
                direction,
                remaining,
                cols,
                rows,
            )
        ]

        choices = [
            symbol
            for symbol, directions in SYMBOL_DIRECTIONS.items()
            if any(direction in free_directions for direction in directions)
        ]

        if not choices:
            raise RuntimeError(
                f"Не найден доступный ход на уровне {level}"
            )

        symbol = rng.choice(choices)
        grid[y * cols + x] = symbol

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


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)

    with OUTPUT.open(
        "w",
        encoding="ascii",
        newline="\n",
    ) as output:
        for level in range(1, MAX_LEVEL + 1):
            output.write(generate_level(level) + "\n")

    print(f"Создано уровней: {MAX_LEVEL}")
    print(f"Файл: {OUTPUT}")


if __name__ == "__main__":
    main()
