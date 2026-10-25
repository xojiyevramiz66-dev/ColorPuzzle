
#!/usr/bin/env python3
"""Проверка решаемости всех уровней ColorPuzzle."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LEVELS_FILE = ROOT / "app/src/main/assets/levels.dat"
EXPECTED_LEVELS = 500_000

DIRECTIONS = {
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

ALLOWED = set(".") | set(DIRECTIONS)


def ray_is_clear(x, y, dx, dy, occupied, cols, rows):
    x += dx
    y += dy

    while 0 <= x < cols and 0 <= y < rows:
        if (x, y) in occupied:
            return False
        x += dx
        y += dy

    return True


def is_solvable(cols, rows, grid):
    occupied = {
        (i % cols, i // cols)
        for i, symbol in enumerate(grid)
        if symbol != "."
    }

    directions = {
        (i % cols, i // cols): DIRECTIONS[symbol]
        for i, symbol in enumerate(grid)
        if symbol != "."
    }

    # Удаление блока убирает препятствие и не может
    # заблокировать путь, который уже был свободен.
    removed = 0

    while occupied:
        removable = None

        for x, y in occupied:
            if any(
                ray_is_clear(
                    x, y, dx, dy, occupied, cols, rows
                )
                for dx, dy in directions[(x, y)]
            ):
                removable = (x, y)
                break

        if removable is None:
            return False, removed

        occupied.remove(removable)
        removed += 1

    return True, removed


def main():
    if not LEVELS_FILE.is_file():
        raise SystemExit(f"ОШИБКА: файл не найден: {LEVELS_FILE}")

    checked = 0
    total_blocks = 0

    with LEVELS_FILE.open("r", encoding="ascii") as file:
        for line_number, raw in enumerate(file, start=1):
            parts = raw.rstrip("\r\n").split("|", 3)

            if len(parts) != 4:
                raise SystemExit(
                    f"ОШИБКА: строка {line_number}: неверный формат"
                )

            try:
                level, cols, rows = map(int, parts[:3])
            except ValueError:
                raise SystemExit(
                    f"ОШИБКА: уровень {line_number}: неверные числа"
                )

            grid = parts[3]

            if level != line_number:
                raise SystemExit(
                    f"ОШИБКА: ожидался уровень {line_number}, "
                    f"получен {level}"
                )

            if not (4 <= cols <= 10 and 4 <= rows <= 10):
                raise SystemExit(
                    f"ОШИБКА: уровень {level}: поле {cols}x{rows}"
                )

            if len(grid) != cols * rows:
                raise SystemExit(
                    f"ОШИБКА: уровень {level}: неверная длина поля"
                )

            invalid = set(grid) - ALLOWED
            if invalid:
                raise SystemExit(
                    f"ОШИБКА: уровень {level}: "
                    f"неизвестные символы {sorted(invalid)}"
                )

            solvable, removed = is_solvable(cols, rows, grid)

            if not solvable:
                raise SystemExit(
                    f"УРОВЕНЬ НЕ РЕШАЕТСЯ: {level}; "
                    f"удалено блоков: {removed}"
                )

            checked += 1
            total_blocks += removed

            if checked % 10_000 == 0:
                print(f"Проверено уровней: {checked:,}")

    if checked != EXPECTED_LEVELS:
        raise SystemExit(
            f"ОШИБКА: ожидалось {EXPECTED_LEVELS} уровней, "
            f"найдено {checked}"
        )

    print(
        f"УСПЕХ: все {checked:,} уровней решаемы. "
        f"Проверено блоков: {total_blocks:,}."
    )


if __name__ == "__main__":
    main()
