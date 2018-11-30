package org.reactome.server.tools.reaction.exporter.layout.algorithm.breathe;

import java.lang.reflect.Array;
import java.util.StringJoiner;

/**
 * Generic matrix, indexed by row and col. All rows have the same number of columns. It wraps an Array of Arrays, and
 * contain operations to add or remove rows and columns and work with columns as rows.
 *
 * @param <T> type of elements in grid
 */
public class Grid<T> {

    private int rows;
    private int columns;
    private T[][] grid;
    private Class<?> clz;

    Grid(Class<T> clz, T[][] grid) {
        this.clz = clz;
        this.grid = grid;
        rows = grid.length;
        columns = grid[0].length;
    }

    Grid(Class<T> clz) {
        this(clz, 0, 0);
    }

    private Grid(Class<T> clz, int rows, int columns) {
        this.clz = clz;
        this.rows = rows;
        this.columns = columns;
        this.grid = createGrid(rows, columns);
    }

    int getColumns() {
        return columns;
    }

    int getRows() {
        return rows;
    }

    T[][] getGrid() {
        return grid;
    }

    T get(int row, int column) {
        return grid[row][column];
    }

    void set(int row, int column, T element) {
        if (rows <= row)
            insertRows(rows, row - rows + 1);
        if (columns <= column)
            insertColumns(columns, column - columns + 1);
        grid[row][column] = element;
    }

    private void insertRows(int index, int n) {
        if (n <= 0) return;
        final T[][] rtn = createGrid(rows + n, columns);
        if (index >= 0) System.arraycopy(grid, 0, rtn, 0, index);
        if (rows - index >= 0) System.arraycopy(grid, index, rtn, index + n, rows - index);
        rows += n;
        grid = rtn;
    }

    private void insertColumns(int index, int n) {
        if (n <= 0) return;
        final T[][] rtn = createGrid(rows, columns + n);
        for (int r = 0; r < rows; r++) {
            if (index >= 0) System.arraycopy(grid[r], 0, rtn[r], 0, index);
            if (columns - index >= 0)
                System.arraycopy(grid[r], index, rtn[r], index + n, columns - index);
        }
        columns += n;
        grid = rtn;
    }

    T[] getRow(int row) {
        return row < rows ? grid[row] : null;
    }

    T[] getColumn(int col) {
        if (col < columns) {
            final T[] rtn = createArray(rows);
            for (int i = 0; i < grid.length; i++) {
                final T[] row = grid[i];
                rtn[i] = row[col];
            }
            return rtn;
        }
        return null;
    }

    void removeRows(int index, int n) {
        n = Math.min(n, rows - index);
        if (n <= 0) return;
        int p = index + n;
        final T[][] rtn = createGrid(rows - n, columns);
        if (index >= 0) System.arraycopy(grid, 0, rtn, 0, index);
        if (p < rows) System.arraycopy(grid, p, rtn, index, rows - p);
        rows -= n;
        grid = rtn;
    }

    void removeColumns(int index, int n) {
        n = Math.min(n, columns - index);
        if (n <= 0) return;
        int p = index + n;
        final T[][] rtn = createGrid(rows, columns - n);
        for (int r = 0; r < rows; r++) {
            if (index >= 0) System.arraycopy(grid[r], 0, rtn[r], 0, index);
            if (p < columns) System.arraycopy(grid[r], p, rtn[r], index, columns - p);
        }
        columns -= n;
        grid = rtn;
    }


    @SuppressWarnings("unchecked")
    private T[] createArray(int n) {
        return (T[]) Array.newInstance(clz, n);
    }

    @SuppressWarnings("unchecked")
    private T[][] createGrid(int rows, int cols) {
        return (T[][]) Array.newInstance(clz, rows, cols);
    }

    @Override
    public String toString() {
        final StringJoiner rtn = new StringJoiner(System.lineSeparator());
        for (int r = 0; r < rows; r++) {
            final StringJoiner line = new StringJoiner(" ");
            for (int c = 0; c < columns; c++) {
                if (grid[r][c] == null) line.add("-");
                else line.add(String.valueOf(grid[r][c].toString().charAt(0)));
            }
            rtn.add(line.toString());
        }
        return rtn.toString();
    }
}
