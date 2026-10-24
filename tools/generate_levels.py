#!/usr/bin/env python3

from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "levels.dat"

MAX_LEVEL = 100_000
SEED_BASE = 20261024

# Все направления — прямолинейные.
# Q отображается как ↴, но движется только вниз.
# W отображается как ↵, но движется только влево.
SYMBOL_DIRECTIONS = {
    "U": ((0, -1),),
    "D": ((0, 1),),
    "L": ((-1, 0),),
    "R": ((1, 0),),
    "V": ((0, -1), (0, 1)),
    "B": ((-1, 0), (1, 0)),
    "Q": ((0, 1),),
    "W": ((-1, 0),),
}

# Вес выбора каждого типа стрелки.
# При равной доступности направлений обычные стрелки
# составляют около 83%, двойные — 12,5%,
# изогнутые — около 4,2%.
SYMBOL_WEIGHTS = {
    "U": 10,
    "D": 10,
    "L": 10,
    "R": 10,
    "V": 3,
    "B": 3,
    "Q": 1,
    "W": 1,
}

ALL_DIRECTIONS = tuple(
    dict.fromkeys(
        direction
        for directions in SYMBOL_DIRECTIONS.values()
        for direction in directions
    )
)


def board_size(level: int) -> tuple[int, int]:
    """Возвращает размер игрового поля для уровня."""

    stage = (max(1, level) - 1) // 5

    for first in range(4, 11):
        stages_in_row = 11 - first

        if stage < stages_in_row:
            return first, first + stage

        stage -= stages_in_row

    return 10, 10


def density_for_level(level: int) -> float:
    """Плотность заполнения игрового поля."""

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
    """Проверяет свободный прямой путь."""

    dx, dy = direction

    x += dx
    y += dy

    while 0 <= x < cols and 0 <= y < rows:
        if (x, y) in occupied:
            return False

        x += dx
        y += dy

    return True


def choose_symbol(
    free_directions: list[tuple[int, int]],
    rng: random.Random,
) -> str:
    """Выбирает доступную стрелку с учётом её веса."""

    choices = [
        symbol
        for symbol, directions in SYMBOL_DIRECTIONS.items()
        if any(
            direction in free_directions
            for direction in directions
        )
    ]

    if not choices:
        raise RuntimeError("Не найден доступный тип стрелки")

    return rng.choices(
        choices,
        weights=[SYMBOL_WEIGHTS[symbol] for symbol in choices],
        k=1,
    )[0]


def generate_level(level: int) -> str:
    """Создаёт один уровень, снимая доступные блоки по очереди."""

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
        # Выбираем край поля. Это помогает обеспечить
        # последовательность удаления блоков.
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

        # Проверяем направления, используя только прямые пути.
        free_directions = [
            direction
            for direction in ALL_DIRECTIONS
            if ray_clear(
                x,
                y,
                direction,
                remaining - {(x, y)},
                cols,
                rows,
            )
        ]

        if not free_directions:
            raise RuntimeError(
                f"Не найден доступный ход на уровне {level}"
            )

        symbol = choose_symbol(free_directions, rng)

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
    """Генерирует levels.dat и печатает статистику."""

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)

    totals = {symbol: 0 for symbol in SYMBOL_DIRECTIONS}
    total_blocks = 0

    print(f"Генерация {MAX_LEVEL} уровней...")

    with OUTPUT.open(
        "w",
        encoding="ascii",
        newline="\n",
    ) as output:
        for level in range(1, MAX_LEVEL + 1):
            record = generate_level(level)
            output.write(record + "\n")

            grid = record.split("|", 3)[3]

            for symbol in totals:
                totals[symbol] += grid.count(symbol)

            total_blocks += sum(
                1 for symbol in grid if symbol != "."
            )

            if level % 5_000 == 0:
                print(f"Создано уровней: {level}/{MAX_LEVEL}")

    print()
    print("Генерация завершена.")
    print(f"Уровней: {MAX_LEVEL}")
    print(f"Блоков: {total_blocks}")

    if total_blocks:
        print("\nРаспределение стрелок:")

        for symbol, count in totals.items():
            percent = count * 100 / total_blocks
            print(f"{symbol}: {count} ({percent:.2f}%)")

    print(f"\nФайл: {OUTPUT}")


if __name__ == "__main__":
    main()
