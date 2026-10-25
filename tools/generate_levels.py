
#!/usr/bin/env python3

from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "levels.dat"

MAX_LEVEL = 500_000
SEED_BASE = 20261024

# U/D/L/R — обычные стрелки.
# V — вверх или вниз.
# B — влево или вправо.
# Q/W/E/A — дополнительные типы стрелок.
SYMBOL_DIRECTIONS = {
    "U": ((0, -1),),
    "D": ((0, 1),),
    "L": ((-1, 0),),
    "R": ((1, 0),),
    "V": ((0, -1), (0, 1)),
    "B": ((-1, 0), (1, 0)),
    "Q": ((0, 1),),
    "W": ((-1, 0),),
    "E": ((0, -1),),
    "A": ((1, 0),),
}

# Базовые веса обычных стрелок.
SYMBOL_WEIGHTS = {
    "U": 10,
    "D": 10,
    "L": 10,
    "R": 10,
    "V": 3,
    "B": 3,
    "Q": 1,
    "W": 1,
    "E": 1,
    "A": 1,
}

ALL_DIRECTIONS = tuple(
    dict.fromkeys(
        direction
        for directions in SYMBOL_DIRECTIONS.values()
        for direction in directions
    )
)


def board_size(level: int) -> tuple[int, int]:
    """Возвращает размер поля для уровня."""
    stage = (max(1, level) - 1) // 5

    for first in range(4, 11):
        stages_in_row = 11 - first

        if stage < stages_in_row:
            return first, first + stage

        stage -= stages_in_row

    return 10, 10


def density_for_level(level: int) -> float:
    """Постепенно увеличивает плотность игрового поля."""
    if level <= 100:
        return 0.68 + 0.10 * ((level - 1) / 99)

    if level <= 1_000:
        return 0.78 + 0.08 * ((level - 101) / 899)

    if level <= 10_000:
        return 0.86 + 0.06 * ((level - 1_001) / 8_999)

    if level <= 50_000:
        return 0.92 + 0.04 * ((level - 10_001) / 39_999)

    if level <= 90_000:
        return 0.96 + 0.015 * ((level - 50_001) / 39_999)

    return 0.975 + 0.015 * ((level - 90_001) / 9_999)


def ray_clear(
    x: int,
    y: int,
    direction: tuple[int, int],
    occupied: set[tuple[int, int]],
    cols: int,
    rows: int,
) -> bool:
    """Проверяет, свободен ли прямой путь от блока до края поля."""
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
    level: int,
) -> str:
    """Выбирает стрелку, все направления которой свободны."""
    choices = [
        symbol
        for symbol, directions in SYMBOL_DIRECTIONS.items()
        if all(
            direction in free_directions
            for direction in directions
        )
    ]

    if not choices:
        raise RuntimeError(
            "Не найден доступный тип стрелки"
        )

    progress = min(
        1.0,
        max(0.0, (level - 1) / (MAX_LEVEL - 1)),
    )

    # Чем выше уровень, тем чаще встречаются двунаправленные блоки.
    multi_weight = 3 + int(progress * 9)

    # Изогнутые стрелки становятся немного чаще.
    curve_weight = 1 + int(progress * 2)

    weights = []

    for symbol in choices:
        if symbol in ("V", "B"):
            weights.append(multi_weight)
        elif symbol in ("Q", "W", "E", "A"):
            weights.append(curve_weight)
        else:
            weights.append(SYMBOL_WEIGHTS[symbol])

    return rng.choices(
        choices,
        weights=weights,
        k=1,
    )[0]


def generate_level(level: int) -> str:
    """Создаёт уровень с гарантированным порядком удаления блоков."""
    if not 1 <= level <= MAX_LEVEL:
        raise ValueError(
            f"Номер уровня должен быть от 1 до {MAX_LEVEL}"
        )

    cols, rows = board_size(level)
    total = cols * rows

    rng = random.Random(SEED_BASE + level * 1009)

    target = max(
        1,
        min(
            total - 1,
            int(total * density_for_level(level)),
        ),
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

    min_x = min(
        x for x, count in enumerate(count_x) if count
    )
    max_x = max(
        x for x, count in enumerate(count_x) if count
    )
    min_y = min(
        y for y, count in enumerate(count_y) if count
    )
    max_y = max(
        y for y, count in enumerate(count_y) if count
    )

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

        edge_set = (
            by_x[edge]
            if axis == "x"
            else by_y[edge]
        )

        x, y = rng.choice(tuple(edge_set))

        # Проверяем пути с учётом оставшихся блоков.
        occupied_after_removal = remaining - {(x, y)}

        free_directions = [
            direction
            for direction in ALL_DIRECTIONS
            if ray_clear(
                x,
                y,
                direction,
                occupied_after_removal,
                cols,
                rows,
            )
        ]

        if not free_directions:
            raise RuntimeError(
                f"Не найден доступный ход на уровне {level}"
            )

        symbol = choose_symbol(
            free_directions,
            rng,
            level,
        )

        grid[y * cols + x] = symbol

        # Удаляем блок из рабочих структур.
        remaining.remove((x, y))

        by_x[x].remove((x, y))
        by_y[y].remove((x, y))

        count_x[x] -= 1
        count_y[y] -= 1

        # Обновляем границы оставшихся блоков.
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
    """Создаёт levels.dat и выводит статистику."""
    OUTPUT.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    totals = {
        symbol: 0
        for symbol in SYMBOL_DIRECTIONS
    }

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
                1
                for symbol in grid
                if symbol != "."
            )

            if level % 5_000 == 0:
                print(
                    f"Создано уровней: "
                    f"{level}/{MAX_LEVEL}"
                )

    print()
    print("Генерация завершена.")
    print(f"Уровней: {MAX_LEVEL}")
    print(f"Блоков: {total_blocks}")

    if total_blocks:
        print("\nРаспределение стрелок:")

        for symbol, count in totals.items():
            percent = count * 100 / total_blocks
            print(
                f"{symbol}: {count} ({percent:.2f}%)"
            )

    print(f"\nФайл: {OUTPUT}")


if __name__ == "__main__":
    main()
